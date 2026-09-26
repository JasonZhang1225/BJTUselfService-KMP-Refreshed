package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class PhyVlabExternalUrlTest {
    @Test
    fun upgradesOnlyTheLeadingHttpScheme() {
        val url = "http://phyvlab.bjtu.edu.cn/course?next=http://example.org/path"
        assertEquals(
            "https://phyvlab.bjtu.edu.cn/course?next=http://example.org/path",
            upgradePhyVlabUrlToHttps(url),
        )
    }

    @Test
    fun leavesAnHttpsUrlAndItsQueryUntouched() {
        val url = "https://phyvlab.bjtu.edu.cn/course?next=http://example.org/path"
        assertEquals(url, upgradePhyVlabUrlToHttps(url))
    }
}
