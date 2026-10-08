package team.bjtuss.bjtuselfservice.shared.data.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeFeedSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeField
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord

class HomeChangeFeedCodecTest {
    @Test
    fun roundTripPreservesUnicodeDelimitersAndBaselines() {
        val snapshot = HomeChangeFeedSnapshot(
            baselineDomains = setOf(HomeChangeDomain.GRADES, HomeChangeDomain.HOMEWORK),
            records = listOf(
                HomeChangeRecord(
                    HomeChangeDomain.GRADES,
                    DataChangeKind.MODIFIED,
                    "高等数学:Ⅰ",
                    "80,旧",
                    "90,新",
                    fields = listOf(HomeChangeField("成绩", "80", "90")),
                ),
            ),
        )

        assertEquals(snapshot, decodeHomeChangeFeed(encodeHomeChangeFeed(snapshot)))
    }

    @Test
    fun versionOnePayloadStillDecodesWithoutFields() {
        fun part(value: String) = "${value.length}:$value"
        val encoded = part("1") + part("GRADES,HOMEWORK") + part("1") +
            part("GRADES") + part("MODIFIED") + part("高等数学:Ⅰ") +
            part("80,旧") + part("90,新")
        val decoded = decodeHomeChangeFeed(encoded)
        assertEquals(HomeChangeDomain.GRADES, decoded?.records?.single()?.domain)
        assertEquals("90,新", decoded?.records?.single()?.afterDetail)
        assertEquals(emptyList(), decoded?.records?.single()?.fields)
    }

    @Test
    fun malformedOrOversizedPayloadIsRejected() {
        assertNull(decodeHomeChangeFeed("1:1"))
        assertNull(decodeHomeChangeFeed("1:11:02:10101:"))
    }
}
