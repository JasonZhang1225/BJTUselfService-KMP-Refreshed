package team.bjtuss.bjtuselfservice.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncodedPartIndexTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun oldCheckpointWithoutEncoderNameStillLoads() {
        val checkpoint = json.decodeFromString<EncodedIndex>(
            """{"quality":"HIGH","parts":[{"startUs":0,"endUs":60000000,"audioFrames":2584}],"complete":false,"audioOriginUs":0}""",
        )
        assertNull(checkpoint.encoderName)
        assertEquals(60_000_000L, checkpoint.parts.single().endUs)
    }

    @Test fun checkpointPreservesEncoderNameAcrossRestart() {
        val checkpoint = EncodedIndex("HIGH", listOf(EncodedPart(0, 60_000_000, 2584)), encoderName = "c2.example.avc.encoder")
        assertEquals(checkpoint, json.decodeFromString<EncodedIndex>(json.encodeToString(checkpoint)))
    }
}
