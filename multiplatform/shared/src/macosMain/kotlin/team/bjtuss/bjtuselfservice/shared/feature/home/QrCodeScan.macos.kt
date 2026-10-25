package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.runtime.Composable

/** macOS 原生端首版不接摄像头扫码（需要 AVFoundation 授权与独立权限流程）。 */
@Composable
internal actual fun rememberQrCodeScanner(): ((QrCodeImage, (String) -> Unit) -> Unit)? = null
