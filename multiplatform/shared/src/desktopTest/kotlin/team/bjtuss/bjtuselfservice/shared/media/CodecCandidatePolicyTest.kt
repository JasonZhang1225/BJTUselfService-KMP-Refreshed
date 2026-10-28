package team.bjtuss.bjtuselfservice.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecCandidatePolicyTest {
    private data class Candidate(val name: String, val hardware: Boolean)
    private val candidates = listOf(Candidate("software", false), Candidate("hardware-a", true), Candidate("hardware-b", true))

    @Test fun prefersHardwareAndKeepsSoftwareAsFallback() {
        assertEquals(listOf("hardware-a", "hardware-b", "software"),
            prioritizedCodecCandidates(candidates, null, Candidate::name, Candidate::hardware).map { it.name })
    }

    @Test fun resumedPartsNeverSwitchEncoder() {
        assertEquals(listOf("hardware-b"),
            prioritizedCodecCandidates(candidates, "hardware-b", Candidate::name, Candidate::hardware).map { it.name })
        assertTrue(prioritizedCodecCandidates(candidates, "unavailable", Candidate::name, Candidate::hardware).isEmpty())
    }
}
