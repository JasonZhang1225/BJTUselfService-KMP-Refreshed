package team.bjtuss.bjtuselfservice.shared.network

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/** 教务/智慧教学仍有 GB18030 响应，Apple 端统一用 CoreFoundation 解码。 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun decodeLegacyGb18030OrNull(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""
    val encoding = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
    return bytes.usePinned { pinned ->
        val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        NSString.create(data = data, encoding = encoding)?.toString()
    }
}
