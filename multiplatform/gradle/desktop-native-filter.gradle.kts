// 桌面安装包只运行在一个系统/架构上，但 sqlite-jdbc 与 JNA 的 jar 内置了二十多个平台的原生库
// （sqlite-jdbc 约 26 MB、JNA 约 5 MB，未压缩）。这里用 Gradle artifact transform 在运行时
// classpath 上去掉其它平台的原生库，只保留应用目标平台那一份；类文件与其它资源原样保留。
//
// 使用方：在 apply 之前设置
//   extra["desktopNativeFilter.configuration"] = "runtimeClasspath"
//   extra["desktopNativeFilter.keep"] = listOf("org/sqlite/native/Mac/aarch64/", "com/sun/jna/darwin-aarch64/")

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class StripForeignNatives : TransformAction<StripForeignNatives.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input
        val keep: ListProperty<String>
    }

    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val nativeRoot = when {
            input.name.startsWith("sqlite-jdbc-") -> "org/sqlite/native/"
            input.name.startsWith("jna-") && !input.name.startsWith("jna-platform-") -> "com/sun/jna/"
            else -> null
        }
        if (nativeRoot == null || !input.isFile) {
            outputs.file(inputArtifact)
            return
        }
        val keep = parameters.keep.get().filter { it.startsWith(nativeRoot) }
        check(keep.isNotEmpty()) { "No native directory kept for ${input.name}" }
        // JNA 的类文件也在 com/sun/jna/ 的子目录里（ptr/、win32/ 等），只按原生库扩展名删除。
        val nativeSuffixes = listOf(".so", ".dylib", ".jnilib", ".dll", ".a")
        fun isForeignNative(name: String): Boolean =
            name.startsWith(nativeRoot) &&
                nativeSuffixes.any { name.endsWith(it) } &&
                keep.none { name.startsWith(it) }
        val output = outputs.file(input.name)
        var kept = 0
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    if (isForeignNative(entry.name)) continue
                    if (keep.any { entry.name.startsWith(it) } && nativeSuffixes.any { entry.name.endsWith(it) }) kept += 1
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        check(kept > 0) { "${input.name} has no native library under $keep" }
    }
}

val desktopNativeFiltered = Attribute.of("bjtu.desktopNativeFiltered", Boolean::class.javaObjectType)
@Suppress("UNCHECKED_CAST")
val desktopNativeKeep = extra["desktopNativeFilter.keep"] as List<String>
val desktopNativeConfiguration = extra["desktopNativeFilter.configuration"] as String

dependencies {
    attributesSchema { attribute(desktopNativeFiltered) }
    artifactTypes.getByName("jar").attributes.attribute(desktopNativeFiltered, false)
    registerTransform(StripForeignNatives::class) {
        from.attribute(desktopNativeFiltered, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(desktopNativeFiltered, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        parameters.keep.set(desktopNativeKeep)
    }
}

configurations.named(desktopNativeConfiguration) {
    attributes.attribute(desktopNativeFiltered, true)
}
