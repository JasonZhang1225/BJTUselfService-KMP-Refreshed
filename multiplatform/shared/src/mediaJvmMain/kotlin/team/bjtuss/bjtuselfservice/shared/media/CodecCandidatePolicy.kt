package team.bjtuss.bjtuselfservice.shared.media

/** 有已完成分段时只能续用其编码器；否则按硬件、软件的顺序尝试。 */
internal fun <T> prioritizedCodecCandidates(
    candidates: List<T>,
    pinnedName: String?,
    name: (T) -> String,
    isHardware: (T) -> Boolean,
): List<T> = candidates.asSequence()
    .filter { pinnedName == null || name(it) == pinnedName }
    .sortedBy { if (isHardware(it)) 0 else 1 }
    .toList()
