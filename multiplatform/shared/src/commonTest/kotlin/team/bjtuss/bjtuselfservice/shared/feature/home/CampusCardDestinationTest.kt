package team.bjtuss.bjtuselfservice.shared.feature.home

import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampusCardDestinationTest {
    @Test
    fun iosUsesUserConfirmedWeChatMiniProgramLink() {
        val destination = campusCardDestination(PlatformFamily.IOS)

        assertEquals("https://wxaurl.cn/RLEw5IMZRKl", destination.url)
        assertEquals(CampusCardAction.OpenUrl, destination.action)
        assertEquals("打开完美校园", destination.confirmLabel)
    }

    @Test
    fun macOsShowsQrCodeForTheSameMiniProgramLink() {
        val destination = campusCardDestination(PlatformFamily.MacOS)

        assertEquals(CampusCardAction.ShowQrCode, destination.action)
        assertEquals("https://wxaurl.cn/RLEw5IMZRKl", destination.url)
        assertEquals("关闭", destination.confirmLabel)
    }

    @Test
    fun androidUsesTheSameMiniProgramLinkInTheDefaultBrowser() {
        val destination = campusCardDestination(PlatformFamily.Android)

        assertEquals(CampusCardAction.OpenUrl, destination.action)
        assertEquals("https://wxaurl.cn/RLEw5IMZRKl", destination.url)
        assertEquals("打开完美校园", destination.confirmLabel)
        assertTrue(destination.message.contains("系统默认浏览器"))
    }

    @Test
    fun qrMatrixHasStableSquareModulesAndFinderPatterns() {
        assertEquals(31, WECHAT_MINI_PROGRAM_QR_MATRIX.size)
        assertTrue(WECHAT_MINI_PROGRAM_QR_MATRIX.all { row ->
            row.length == 31 && row.all { it == '0' || it == '1' }
        })
        assertEquals("01111111", WECHAT_MINI_PROGRAM_QR_MATRIX[1].take(8))
        assertEquals("01111111", WECHAT_MINI_PROGRAM_QR_MATRIX[7].take(8))
        assertEquals("0000000000000000000000000000000", WECHAT_MINI_PROGRAM_QR_MATRIX.last())
    }

    @Test
    fun networkPaymentQrMatrixMatchesTheUserProvidedVersionFourCode() {
        assertEquals(33, NETWORK_PAYMENT_QR_MATRIX.size)
        assertTrue(NETWORK_PAYMENT_QR_MATRIX.all { row ->
            row.length == 33 && row.all { it == '0' || it == '1' }
        })
        assertEquals("1111111", NETWORK_PAYMENT_QR_MATRIX.first().take(7))
        assertEquals("1111111", NETWORK_PAYMENT_QR_MATRIX.first().takeLast(7))
        assertEquals("1111111", NETWORK_PAYMENT_QR_MATRIX[26].take(7))
    }
}
