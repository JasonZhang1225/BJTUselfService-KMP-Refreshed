package team.bjtuss.bjtuselfservice.shared.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 在系统默认 [X509TrustManager] 基础上，为配置漏发中间证书的学校 `*.bjtu.edu.cn` 服务端
 * 提供三层安全中间证书补链，再交回系统默认 TrustManager 完成完整 PKIX 校验：
 * 1. 内置已知官方中间证书 + 会话期间已验证的同校中间证书内存缓存（0ms）；
 * 2. 同校公开 HTTPS 节点（`sso.bjtu.edu.cn`、`aa.bjtu.edu.cn`）纯 HTTPS 自动学习；
 * 3. 叶子/中间证书 AIA (`id-ad-caIssuers`) 受限动态拉取（公网地址、禁重定向、64 KiB 上限、
 *    3 秒超时；Android 保持全局禁明文策略，仅允许 HTTPS AIA，桌面端兼容 HTTP/HTTPS AIA）。
 *
 * 绝不把中间证书加入信任锚，也绝不放宽域名或日期/签名检查。
 */
internal open class SchoolTlsTrustManager(
    protected val delegate: X509TrustManager = systemDefaultX509TrustManager(),
    private val supplementaryIntermediates: List<X509Certificate> = defaultSchoolSupplementaryIntermediates(),
    private val peerIntermediateLoader: () -> List<X509Certificate> = defaultPeerIntermediateLoader(delegate),
    private val aiaCertificateFetcher: (String) -> List<X509Certificate> = defaultAiaCertificateFetcher(delegate),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : X509ExtendedTrustManager() {

    private val cacheLock = Any()
    private val cachedIntermediates = mutableListOf<X509Certificate>()
    private var failedPeerProbeUntilMillis: Long = 0L
    private val failedAiaUntilMillis = LinkedHashMap<String, Long>()
    private val reentrantGuard = ThreadLocal<Boolean>()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) {
            extended.checkClientTrusted(chain, authType, socket)
        } else {
            delegate.checkClientTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) {
            extended.checkClientTrusted(chain, authType, engine)
        } else {
            delegate.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verifyWithCompletion(chain) { candidate ->
            delegate.checkServerTrusted(candidate, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val extended = delegate as? X509ExtendedTrustManager
        verifyWithCompletion(chain) { candidate ->
            if (extended != null) {
                extended.checkServerTrusted(candidate, authType, socket)
            } else {
                delegate.checkServerTrusted(candidate, authType)
            }
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val extended = delegate as? X509ExtendedTrustManager
        verifyWithCompletion(chain) { candidate ->
            if (extended != null) {
                extended.checkServerTrusted(candidate, authType, engine)
            } else {
                delegate.checkServerTrusted(candidate, authType)
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    protected fun <R> verifyWithCompletion(
        chain: Array<out X509Certificate>?,
        verify: (Array<X509Certificate>) -> R,
    ): R {
        val raw = chain?.map { it }?.toTypedArray() ?: arrayOf()
        return try {
            verify(raw).also {
                recordVerifiedIntermediates(raw)
            }
        } catch (original: CertificateException) {
            if (raw.isEmpty() || !leafMatchesBjtuDomain(raw[0]) || reentrantGuard.get() == true) {
                throw original
            }
            reentrantGuard.set(true)
            try {
                verifyWithFallbacks(raw, original, verify)
            } finally {
                reentrantGuard.remove()
            }
        }
    }

    private fun <R> verifyWithFallbacks(
        raw: Array<X509Certificate>,
        original: CertificateException,
        verify: (Array<X509Certificate>) -> R,
    ): R {
        // 第 1 层：内置 + 已缓存同校中间证书补链（0ms）
        completeChainFrom(raw, allKnownIntermediates())?.let { tier1 ->
            try {
                return verify(tier1)
            } catch (_: CertificateException) {
                // 继续尝试第 2 层
            }
        }

        // 第 2 层：通过同校公开 HTTPS 节点自动学习新中间证书
        val now = nowMillis()
        val shouldProbePeers = synchronized(cacheLock) { now >= failedPeerProbeUntilMillis }
        if (shouldProbePeers) {
            val learned = runCatching { peerIntermediateLoader() }.getOrDefault(emptyList())
            val added = cacheIntermediates(learned)
            if (added == 0) {
                synchronized(cacheLock) {
                    failedPeerProbeUntilMillis = nowMillis() + FAILURE_COOLDOWN_MILLIS
                }
            }
            completeChainFrom(raw, allKnownIntermediates())?.let { tier2 ->
                try {
                    return verify(tier2)
                } catch (_: CertificateException) {
                    // 继续尝试第 3 层
                }
            }
        }

        // 第 3 层：受限 AIA (id-ad-caIssuers) 动态拉取（最多补 3 级）
        val working = (completeChainFrom(raw, allKnownIntermediates()) ?: raw).toMutableList()
        repeat(MAX_CHAIN_DEPTH) {
            val tail = working.last()
            if (tail.subjectX500Principal == tail.issuerX500Principal) return@repeat
            val urls = extractAiaCaIssuersUrls(tail)
                .filter(::isAllowedAiaUrl)
                .take(MAX_AIA_URLS_PER_CERT)
            var appended = false
            for (url in urls) {
                val currentNow = nowMillis()
                val onCooldown = synchronized(cacheLock) {
                    (failedAiaUntilMillis[url] ?: 0L) > currentNow
                }
                if (onCooldown) continue
                val fetched = runCatching { aiaCertificateFetcher(url) }.getOrDefault(emptyList())
                val matching = fetched.firstOrNull { candidate ->
                    isValidIntermediateCandidate(candidate, tail, working)
                }
                if (matching != null) {
                    cacheIntermediates(listOf(matching))
                    working.add(matching)
                    appended = true
                    try {
                        return verify(working.toTypedArray())
                    } catch (_: CertificateException) {
                        // 可能是多级中间链，继续向更高一级解析 AIA
                        break
                    }
                } else {
                    recordFailedAiaUrl(url, currentNow + FAILURE_COOLDOWN_MILLIS)
                }
            }
            if (!appended) return@repeat
        }

        throw original
    }

    private fun recordVerifiedIntermediates(chain: Array<X509Certificate>) {
        if (chain.size < 2 || !leafMatchesBjtuDomain(chain[0])) return
        cacheIntermediates(chain.drop(1))
    }

    private fun allKnownIntermediates(): List<X509Certificate> = synchronized(cacheLock) {
        if (cachedIntermediates.isEmpty()) supplementaryIntermediates
        else supplementaryIntermediates + cachedIntermediates
    }

    private fun cacheIntermediates(candidates: List<X509Certificate>): Int {
        if (candidates.isEmpty()) return 0
        var added = 0
        synchronized(cacheLock) {
            for (candidate in candidates) {
                if (!isCaIntermediate(candidate)) continue
                val alreadyInBundled = supplementaryIntermediates.any { it.encoded.contentEquals(candidate.encoded) }
                val alreadyInCache = cachedIntermediates.any { it.encoded.contentEquals(candidate.encoded) }
                if (!alreadyInBundled && !alreadyInCache) {
                    if (cachedIntermediates.size >= MAX_CACHED_INTERMEDIATES) {
                        cachedIntermediates.removeAt(0)
                    }
                    cachedIntermediates.add(candidate)
                    added++
                }
            }
        }
        return added
    }

    private fun recordFailedAiaUrl(url: String, untilMillis: Long) {
        synchronized(cacheLock) {
            failedAiaUntilMillis.remove(url)
            if (failedAiaUntilMillis.size >= MAX_FAILED_AIA_URLS) {
                val oldestKey = failedAiaUntilMillis.entries.firstOrNull()?.key
                if (oldestKey != null) failedAiaUntilMillis.remove(oldestKey)
            }
            failedAiaUntilMillis[url] = untilMillis
        }
    }

    private fun completeChainFrom(
        chain: Array<X509Certificate>,
        pool: List<X509Certificate>,
    ): Array<X509Certificate>? {
        if (chain.isEmpty() || pool.isEmpty() || !leafMatchesBjtuDomain(chain[0])) return null
        val completed = chain.toMutableList()
        repeat(MAX_CHAIN_DEPTH) {
            val tail = completed.last()
            if (tail.subjectX500Principal == tail.issuerX500Principal) return@repeat
            val next = pool.firstOrNull { candidate ->
                isValidIntermediateCandidate(candidate, tail, completed)
            } ?: return@repeat
            completed.add(next)
        }
        return if (completed.size > chain.size) completed.toTypedArray() else null
    }

    companion object {
        private const val DNS_SAN_TYPE = 2
        private const val BJTU_DOMAIN = "bjtu.edu.cn"
        private const val MAX_CHAIN_DEPTH = 3
        private const val MAX_CACHED_INTERMEDIATES = 16
        private const val MAX_FAILED_AIA_URLS = 64
        private const val MAX_AIA_URLS_PER_CERT = 2
        private const val FAILURE_COOLDOWN_MILLIS = 5 * 60_000L

        internal val DEFAULT_PEER_HOSTS = listOf("sso.bjtu.edu.cn", "aa.bjtu.edu.cn")

        private val sharedDefault by lazy { SchoolTlsTrustManager() }

        fun default(): SchoolTlsTrustManager = sharedDefault

        internal fun leafMatchesBjtuDomain(leaf: X509Certificate): Boolean {
            val sans = runCatching { leaf.subjectAlternativeNames }.getOrNull() ?: return false
            return sans.any { entry ->
                val type = (entry.getOrNull(0) as? Number)?.toInt()
                val dns = (entry.getOrNull(1) as? String)?.trim()?.lowercase()?.trimEnd('.')
                type == DNS_SAN_TYPE &&
                    dns != null &&
                    ('*' !in dns || (dns.startsWith("*.") && dns.count { it == '*' } == 1)) &&
                    (dns == BJTU_DOMAIN || dns.endsWith(".$BJTU_DOMAIN"))
            }
        }

        internal fun isCaIntermediate(cert: X509Certificate): Boolean =
            cert.basicConstraints >= 0 && cert.subjectX500Principal != cert.issuerX500Principal

        internal fun isValidIntermediateCandidate(
            candidate: X509Certificate,
            child: X509Certificate,
            existing: List<X509Certificate>,
        ): Boolean =
            isCaIntermediate(candidate) &&
                candidate.subjectX500Principal == child.issuerX500Principal &&
                existing.none { it.encoded.contentEquals(candidate.encoded) } &&
                runCatching { child.verify(candidate.publicKey) }.isSuccess
    }
}

private const val AIA_EXTENSION_OID = "1.3.6.1.5.5.7.1.1"
private const val NETWORK_TIMEOUT_MILLIS = 3_000
private const val MAX_AIA_CERT_BYTES = 64 * 1024
private val ID_AD_CA_ISSUERS_OID_BYTES = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)
private val FORBIDDEN_AIA_HOST_SUFFIXES = setOf(
    "local",
    "localhost",
    "internal",
    "arpa",
    "lan",
    "home",
    "corp",
    "intranet",
    "localdomain",
)

internal fun defaultPeerIntermediateLoader(
    delegate: X509TrustManager,
    hosts: List<String> = SchoolTlsTrustManager.DEFAULT_PEER_HOSTS,
): () -> List<X509Certificate> = {
    val collected = mutableListOf<X509Certificate>()
    val sslContext = SSLContext.getInstance("TLS").apply {
        // 使用原始系统 TrustManager，只接受系统本身已信任的同校节点。
        init(null, arrayOf<TrustManager>(delegate), null)
    }
    val factory = sslContext.socketFactory
    for (host in hosts) {
        runCatching {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, 443), NETWORK_TIMEOUT_MILLIS)
            rawSocket.soTimeout = NETWORK_TIMEOUT_MILLIS
            val sslSocket = factory.createSocket(rawSocket, host, 443, true) as SSLSocket
            sslSocket.use { socket ->
                socket.sslParameters = socket.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                socket.startHandshake()
                val certs = socket.session.peerCertificates.filterIsInstance<X509Certificate>()
                if (certs.size >= 2 && SchoolTlsTrustManager.leafMatchesBjtuDomain(certs[0])) {
                    certs.drop(1)
                        .filter(SchoolTlsTrustManager::isCaIntermediate)
                        .forEach { cert ->
                            if (collected.none { it.encoded.contentEquals(cert.encoded) }) {
                                collected.add(cert)
                            }
                        }
                }
            }
        }
    }
    collected
}

internal fun defaultAiaCertificateFetcher(
    delegate: X509TrustManager,
): (String) -> List<X509Certificate> = fetch@{ url ->
    if (!isAllowedAiaUrl(url)) return@fetch emptyList()
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return@fetch emptyList()
    val host = uri.host ?: return@fetch emptyList()
    val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return@fetch emptyList()
    if (resolved.isEmpty() || resolved.any { !isPublicRoutableAddress(it) }) return@fetch emptyList()

    val connection = (uri.toURL().openConnection() as? HttpURLConnection) ?: return@fetch emptyList()
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.useCaches = false
        if (connection is HttpsURLConnection) {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(delegate), null)
            }
            connection.sslSocketFactory = sslContext.socketFactory
        }
        val status = connection.responseCode
        if (status !in 200..299) return@fetch emptyList()
        val bytes = connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                total += read
                if (total > MAX_AIA_CERT_BYTES) return@fetch emptyList()
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) return@fetch emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
    } finally {
        connection.disconnect()
    }
}

internal fun isAllowedAiaUrl(rawUrl: String): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty() || trimmed.length > 512) return false
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (uri.userInfo != null) return false
    val port = uri.port
    if (port != -1 && !((scheme == "http" && port == 80) || (scheme == "https" && port == 443))) {
        return false
    }
    val host = uri.host?.trim()?.lowercase()?.trimEnd('.') ?: return false
    if (host.isEmpty() || ':' in host || '[' in host || ']' in host) return false
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() }) return false
    // 要求顶级域名至少 2 个纯 ASCII 字母，彻底排除十进制/八进制/十六进制 IP 字面量。
    val tld = labels.last()
    if (tld.length < 2 || !tld.all { it in 'a'..'z' }) return false
    if (tld in FORBIDDEN_AIA_HOST_SUFFIXES || host == "localhost") return false
    if (!labels.all { label ->
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
    ) {
        return false
    }
    return true
}

internal fun isPublicRoutableAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
        return false
    }
    return when (address) {
        is Inet4Address -> isPublicIpv4(address.address)
        is Inet6Address -> {
            val bytes = address.address
            if (bytes.size != 16) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 拒绝 ULA (fc00::/7) 与 Link-Local (fe80::/10)
            if ((b0 and 0xFE) == 0xFC) return false
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return false
            // 拒绝 IPv4-mapped IPv6 (::ffff:0:0/96) 中的非公网 IPv4
            val isV4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            if (isV4Mapped) {
                isPublicIpv4(bytes.copyOfRange(12, 16))
            } else {
                true
            }
        }
        else -> false
    }
}

private fun isPublicIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    return when {
        b0 == 0 -> false // 0.0.0.0/8
        b0 == 10 -> false // 10.0.0.0/8
        b0 == 100 && (b1 and 0xC0) == 64 -> false // 100.64.0.0/10 CGNAT
        b0 == 127 -> false // 127.0.0.0/8
        b0 == 169 && b1 == 254 -> false // 169.254.0.0/16
        b0 == 172 && b1 in 16..31 -> false // 172.16.0.0/12
        b0 == 192 && b1 == 0 -> false // 192.0.0.0/24 & TEST-NET-1
        b0 == 192 && b1 == 168 -> false // 192.168.0.0/16
        b0 == 198 && (b1 == 18 || b1 == 19) -> false // 198.18.0.0/15
        b0 == 198 && b1 == 51 && b2 == 100 -> false // TEST-NET-2
        b0 == 203 && b1 == 0 && b2 == 113 -> false // TEST-NET-3
        b0 >= 224 -> false // multicast & reserved
        else -> true
    }
}

internal fun extractAiaCaIssuersUrls(cert: X509Certificate): List<String> {
    val extBytes = runCatching { cert.getExtensionValue(AIA_EXTENSION_OID) }.getOrNull() ?: return emptyList()
    val outerOctet = readDerTlv(extBytes, 0, extBytes.size) ?: return emptyList()
    if (outerOctet.tag != 0x04 || outerOctet.nextOffset != extBytes.size) return emptyList()
    val seq = readDerTlv(extBytes, outerOctet.valueOffset, outerOctet.valueEnd) ?: return emptyList()
    if (seq.tag != 0x30) return emptyList()

    val urls = mutableListOf<String>()
    var pos = seq.valueOffset
    var count = 0
    while (pos < seq.valueEnd && count < 32) {
        count++
        val item = readDerTlv(extBytes, pos, seq.valueEnd) ?: break
        pos = item.nextOffset
        if (item.tag != 0x30) continue
        val oid = readDerTlv(extBytes, item.valueOffset, item.valueEnd) ?: continue
        if (oid.tag != 0x06) continue
        val loc = readDerTlv(extBytes, oid.nextOffset, item.valueEnd) ?: continue
        val oidBytes = extBytes.copyOfRange(oid.valueOffset, oid.valueEnd)
        if (oidBytes.contentEquals(ID_AD_CA_ISSUERS_OID_BYTES) && loc.tag == 0x86) {
            val uri = String(extBytes, loc.valueOffset, loc.valueEnd - loc.valueOffset, Charsets.US_ASCII).trim()
            if (uri.isNotEmpty() && uri !in urls) {
                urls.add(uri)
            }
        }
    }
    return urls
}

private data class DerTlv(
    val tag: Int,
    val valueOffset: Int,
    val valueEnd: Int,
) {
    val nextOffset: Int get() = valueEnd
}

private fun readDerTlv(data: ByteArray, offset: Int, limit: Int): DerTlv? {
    if (offset >= limit || limit > data.size || offset < 0) return null
    val tag = data[offset].toInt() and 0xFF
    var cursor = offset + 1
    if (cursor >= limit) return null
    val lenByte = data[cursor++].toInt() and 0xFF
    val length = if ((lenByte and 0x80) == 0) {
        lenByte
    } else {
        val lenCount = lenByte and 0x7F
        if (lenCount == 0 || lenCount > 3 || cursor + lenCount > limit) return null
        var acc = 0
        repeat(lenCount) {
            acc = (acc shl 8) or (data[cursor++].toInt() and 0xFF)
        }
        acc
    }
    if (length < 0 || cursor + length > limit) return null
    return DerTlv(tag = tag, valueOffset = cursor, valueEnd = cursor + length)
}

internal fun systemDefaultX509TrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private val bundledIntermediates by lazy {
    val factory = CertificateFactory.getInstance("X.509")
    GEOTRUST_G2_TLS_CN_2022_CA1_PEM.byteInputStream().use { input ->
        listOf(factory.generateCertificate(input) as X509Certificate)
    }
}

internal fun defaultSchoolSupplementaryIntermediates(): List<X509Certificate> = bundledIntermediates

/**
 * CN=GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1
 * Issuer: CN=DigiCert Global Root G2
 * Serial: 0F06BB09306148267FCA1B71C1DB807D
 * SHA-256: 05:DC:9E:DC:0F:DD:FA:97:5A:14:32:EF:80:6E:C7:80:07:8B:53:62:AD:45:AF:76:DB:15:C9:07:63:0D:B2:5D
 */
private const val GEOTRUST_G2_TLS_CN_2022_CA1_PEM = """
-----BEGIN CERTIFICATE-----
MIIFxjCCBK6gAwIBAgIQDwa7CTBhSCZ/yhtxwduAfTANBgkqhkiG9w0BAQsFADBh
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
MjAeFw0yMjEyMTUwMDAwMDBaFw0zMjEyMTQyMzU5NTlaMFsxCzAJBgNVBAYTAlVT
MRcwFQYDVQQKEw5EaWdpQ2VydCwgSW5jLjEzMDEGA1UEAxMqR2VvVHJ1c3QgRzIg
VExTIENOIFJTQTQwOTYgU0hBMjU2IDIwMjIgQ0ExMIICIjANBgkqhkiG9w0BAQEF
AAOCAg8AMIICCgKCAgEAn1dxOo4OzYa4O1EJd0nhFI5QB/kXAJTHRI6C1J2Gz86B
Ge9+DD8R4vexG7/QnUvV+5887o+G4enlkDwJV1Pehq4i0n+X6VKIPg5cThCx6/o0
3bUkLWld7slhi3Hli/MaosZZuytdU1uCzQlGpaLB2TiTZbDImiVaykdfwl8V6AXP
0Ab4wIcvPggl4qlwyPsBY6NODbP884BmL1ntdXfzecGst30FnAtm4w+PTo0I1T3F
ITYXaNuIJnKonD1xXaN4Ar/rvcpKpntxVyZQ2T1Lb7vlfYISqNF+1ugpTzKnI1z7
xV7tSqXd2vs6Csy6yFN+QLWwMnGWuQkvrO1m+F7V85S9ECpbqn9qNtKl8gIQ7JeZ
BmBdLroW+4j1PDzBWmSWKB+gvRubVSTuoDR3VCtlI4G/Uh+ObF4UStbhKjr1SNCW
JcrB1oF+wWBl8Bvozm4xflyyGDY47KCP/Fn9X5GHDNLQA9R0zqDKRumipp1UXswF
uzH0I+gOuqSdZSsYID9XSVq6adcJ3rIMAcTuODcrPrJssOFKGFUbmY/VxbxMgYHI
/07Zfdxoa+q0osWpofbMhLPZz9UuJFBCLsjgSckkSU02/4itmLm9e3lt6E/4q9Mc
MCaS8+qrLWKG9wQtviC8zGynEXpxjEQE8WyrfeYKyXwZFCPB4rpTw1joJmGFeLUC
AwEAAaOCAX4wggF6MBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFEFOjmmd
9G4l7HgUHH7XzR2Zz/lrMB8GA1UdIwQYMBaAFE4iVCAYlebjbuYP+vq5Eu0GF485
MA4GA1UdDwEB/wQEAwIBhjAdBgNVHSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIw
dAYIKwYBBQUHAQEEaDBmMCMGCCsGAQUFBzABhhdodHRwOi8vb2NzcC5kaWdpY2VydC5jbjA/BggrBgEFBQcwAoYzaHR0cDovL2NhY2VydHMuZGlnaWNlcnQuY24vRGln
aUNlcnRHbG9iYWxSb290RzIuY3J0MEAGA1UdHwQ5MDcwNaAzoDGGL2h0dHA6Ly9j
cmwuZGlnaWNlcnQuY24vRGlnaUNlcnRHbG9iYWxSb290RzIuY3JsMD0GA1UdIAQ2
MDQwCwYJYIZIAYb9bAIBMAcGBWeBDAEBMAgGBmeBDAECATAIBgZngQwBAgIwCAYG
Z4EMAQIDMA0GCSqGSIb3DQEBCwUAA4IBAQAkpDVI+nCKnAEEpDfldytHXUYOr2ys
aGeboE3d1KAN4Gt+eioRvgNdmDKbVFCYY0C6ErIXIvSGXafsprU2dclka/kmH+9U
4q3v5Acx6KwcnEuYLN0QOtrlQ9s3Z4IbIzdYUv8IauXC27a3x99x2WMVxNu1KGJy
0r1Z+Xya1adf9/e1LFj1CGdq9HfQ/HFWP2khzxQZ4u62sOHuZdPgtNidFOhQLC+2
5cNsPgqsaCrGLbSjzni7VN7NhAbTsLhA1oz41vHHB+q4ZzNlC01elmPBOtupe2HM
/lqMA/J/nTxc718THdxpszFllAET1/lFEolnqihpUFPaPjRwU7yZIseS
-----END CERTIFICATE-----
"""
