package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.serialization.Serializable

@Serializable
internal data class EncodedPart(val startUs: Long, val endUs: Long, val audioFrames: Long? = null)

@Serializable
internal data class EncodedIndex(
    val quality: String,
    val parts: List<EncodedPart>,
    val complete: Boolean = false,
    val audioOriginUs: Long? = null,
    val encoderName: String? = null,
)
