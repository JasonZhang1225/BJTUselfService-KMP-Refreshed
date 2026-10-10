package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert

internal enum class CampusAccountEntry { CAMPUS_CARD, NETWORK }

@Composable
internal fun CampusAccountEntryDialog(
    entry: CampusAccountEntry,
    family: PlatformFamily,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    when (entry) {
        CampusAccountEntry.CAMPUS_CARD -> {
            val destination = campusCardDestination(family)
            val uriHandler = LocalUriHandler.current
            AppleSheetOrAlert(
                onDismissRequest = onDismiss,
                title = null,
                confirmLabel = destination.confirmLabel,
                showDismissButton = true,
                onConfirm = {
                    onDismiss()
                    if (destination.action == CampusCardAction.OpenUrl) {
                        val target = destination.url
                        val opened = target != null && runCatching {
                            if (onOpenUrl != null) onOpenUrl(target) else uriHandler.openUri(target)
                        }.isSuccess
                        if (!opened) onError("当前无法打开完美校园链接。")
                    }
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text(
                        destination.message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (destination.action == CampusCardAction.ShowQrCode) {
                        MiniProgramQrCode()
                        Text(
                            "用手机微信扫描",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        CampusAccountEntry.NETWORK -> {
            if (family == PlatformFamily.IOS) {
                AppleSheet(
                    onDismissRequest = onDismiss,
                    title = "校园网充值",
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        NetworkPaymentQrCode()
                        NetworkPaymentInstruction(family)
                    }
                }
            } else {
                AppleSheetOrAlert(
                    onDismissRequest = onDismiss,
                    title = null,
                    confirmLabel = "关闭",
                    showDismissButton = true,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(
                            networkPaymentMessage(family),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Start,
                        )
                        NetworkPaymentQrCode()
                        Text(
                            "用手机微信扫描",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniProgramQrCode() = QrCode(
    matrix = WECHAT_MINI_PROGRAM_QR_MATRIX,
    quietZone = 3,
    description = "完美校园微信小程序二维码",
)

@Composable
private fun NetworkPaymentQrCode() = QrCode(
    matrix = NETWORK_PAYMENT_QR_MATRIX,
    quietZone = 4,
    description = "北京交通大学卡网缴费微信二维码",
)

@Composable
private fun QrCode(
    matrix: List<String>,
    quietZone: Int,
    description: String,
) {
    Surface(
        modifier = Modifier.size(240.dp).semantics { contentDescription = description },
        color = Color.White,
        shape = MaterialTheme.shapes.medium,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val moduleCount = matrix.size + quietZone * 2
            val moduleSize = minOf(size.width, size.height) / moduleCount
            val startX = (size.width - moduleSize * moduleCount) / 2f
            val startY = (size.height - moduleSize * moduleCount) / 2f
            matrix.forEachIndexed { row, values ->
                values.forEachIndexed { column, value ->
                    if (value == '1') {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                startX + (column + quietZone) * moduleSize,
                                startY + (row + quietZone) * moduleSize,
                            ),
                            size = Size(moduleSize + 0.15f, moduleSize + 0.15f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkPaymentInstruction(family: PlatformFamily) {
    val prefix = if (family == PlatformFamily.MacOS) "请使用" else "请将二维码截图或保存到相册，并打开"
    Text(
        buildAnnotatedString {
            append(prefix)
            withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                append("微信扫一扫")
            }
            append("进入卡网缴费页面。")
        },
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun networkPaymentMessage(family: PlatformFamily): String = when (family) {
    PlatformFamily.MacOS -> "请使用手机微信扫描二维码，进入校园网缴费页面。"
    else -> "请将二维码截图或保存到相册，再使用微信扫一扫进入校园网缴费页面。"
}
