package team.bjtuss.bjtuselfservice.shared.update

import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

enum class UpdateSource(val label: String) {
    AUTO("自动选择"), GITHUB("GitHub"), GITEE("Gitee");

    companion object {
        fun fromSetting(value: String?): UpdateSource = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** 公开更新接口独立于学校会话；自动模式先请求 GitHub，失败时再请求完整 Gitee 镜像。 */
object AppUpdateChecker {

    const val REPO = "JasonZhang1225/BJTUselfService-KMP-Refreshed"

    /** 当前构建版本。与 androidApp versionName / 设置页展示一致。 */
    const val CURRENT_VERSION = "1.8.6-KMP"

    const val GITEE_REPO = "zjg1225/BJTUselfService-KMP-Refreshed"
    const val MIRROR_READY = "<!-- bjtu-mirror-ready:v1 -->"

    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("published_at") val publishedAt: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        @Transient val source: UpdateSource = UpdateSource.GITHUB,
    )

    sealed interface Result {
        /** 成功获取到最新 Release（无论是否更新）。 */
        data class Success(val release: Release) : Result

        /** 网络失败或响应不可解析。 */
        data object Unavailable : Result
    }

    @Serializable
    private data class GiteeAsset(val name: String, @SerialName("browser_download_url") val downloadUrl: String)

    @Serializable
    private data class GiteeRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        val prerelease: Boolean = false,
        val assets: List<GiteeAsset> = emptyList(),
    )

    suspend fun fetchLatest(
        transport: SchoolHttpTransport,
        source: UpdateSource = UpdateSource.AUTO,
    ): Result = withContext(Dispatchers.Default) {
        when (source) {
            UpdateSource.GITHUB -> fetchFrom(transport, UpdateSource.GITHUB)
            UpdateSource.GITEE -> fetchFrom(transport, UpdateSource.GITEE)
            UpdateSource.AUTO -> {
                val github = fetchFrom(transport, UpdateSource.GITHUB)
                if (github is Result.Success) github else fetchFrom(transport, UpdateSource.GITEE)
            }
        }
    }

    private suspend fun fetchFrom(transport: SchoolHttpTransport, source: UpdateSource): Result {
        return try {
            // Bound each source separately so an unreachable GitHub cannot block fallback indefinitely.
            withTimeoutOrNull(if (source == UpdateSource.GITHUB) 5_000L else 8_000L) {
                val response = transport.executePublic(SchoolHttpRequest(
                    method = SchoolHttpMethod.GET,
                    url = if (source == UpdateSource.GITHUB) RELEASES_URL else
                        "https://gitee.com/api/v5/repos/$GITEE_REPO/releases?per_page=100",
                    headers = mapOf("Accept" to if (source == UpdateSource.GITHUB) "application/vnd.github+json" else "application/json"),
                ))
                if (response.statusCode !in 200..299) return@withTimeoutOrNull Result.Unavailable
                if (source == UpdateSource.GITHUB) {
                    val latest = json.decodeFromString<List<Release>>(response.bodyText()).firstOrNull { !it.draft }
                    // Preserve GitHub's existing first published release / emergency pre-release behavior.
                    latest?.let { Result.Success(it) } ?: Result.Unavailable
                } else {
                    val latest = json.decodeFromString<List<GiteeRelease>>(response.bodyText())
                        .filter { mirror ->
                            mirror.body?.contains(MIRROR_READY) == true &&
                                mirror.tagName.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+[A-Za-z0-9._-]*")) &&
                                listOf(".apk", ".ipa", ".dmg", ".msi").all { ext ->
                                    mirror.assets.any { asset ->
                                        asset.name.matches(Regex("[A-Za-z0-9._-]+")) && asset.name.endsWith(ext) &&
                                            asset.downloadUrl == "https://gitee.com/$GITEE_REPO/releases/download/${mirror.tagName}/${asset.name}"
                                    }
                                }
                        }
                        // Gitee can reorder releases when an old mirror is retried: compare versions.
                        .maxWithOrNull { a, b -> compareVersions(a.tagName, b.tagName) }
                    latest?.let {
                        Result.Success(Release(it.tagName, it.name, it.body?.replace(MIRROR_READY, "")?.trim(),
                            "https://gitee.com/$GITEE_REPO/releases/tag/${it.tagName}", it.createdAt,
                            it.prerelease, source = UpdateSource.GITEE))
                    } ?: Result.Unavailable
                }
            } ?: Result.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.Unavailable
        }
    }

    /** 是否有更新：远端 tag 规范比较后严格高于 [CURRENT_VERSION]。 */
    fun isNewer(release: Release, current: String = CURRENT_VERSION): Boolean =
        compareVersions(release.tagName, current) > 0

    /**
     * 语义化版本比较：忽略 `v` 前缀，逐段比较点号数字；数字段相同则按后缀
     *（如 `-KMP`）做字典序。与原版 `String.compareTo` 相比能正确处理
     * `1.7.10` vs `1.7.9` 这类数字大小。
     *
     * @return 负数 a 旧于 b；0 相等；正数 a 新于 b。
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String> {
            val noV = v.removePrefix("v")
            val core = noV.substringBefore('-')
            val suffix = noV.substringAfter('-', "")
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            return nums to suffix
        }
        val (numsA, suffixA) = parts(a)
        val (numsB, suffixB) = parts(b)
        val len = min(numsA.size, numsB.size)
        for (i in 0 until len) {
            if (numsA[i] != numsB[i]) return numsA[i].compareTo(numsB[i])
        }
        if (numsA.size != numsB.size) return numsA.size.compareTo(numsB.size)
        return suffixA.compareTo(suffixB)
    }
}
