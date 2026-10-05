package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualTopScrollOffsetTest {
    @Test
    fun shortPageElasticDragAndReturnRemainVisibleWithZeroLogicalScrolling() {
        val contentPositions = listOf(100f, 79f, 58f, 17f, -61f, 17f, 58f, 79f, 100f)
        assertEquals(
            listOf(0f, 21f, 42f, 83f, 161f, 83f, 42f, 21f, 0f),
            contentPositions.map { resolveVisualTopScrollOffset(0f, 100f, it) },
        )
    }

    @Test
    fun combinesLogicalScrollingWithPlacementAndHandlesInitialLayoutAndDownwardBounce() {
        assertEquals(100f, resolveVisualTopScrollOffset(100f, 20f, 20f))
        assertEquals(121f, resolveVisualTopScrollOffset(100f, 20f, -1f))
        assertEquals(79f, resolveVisualTopScrollOffset(100f, 20f, 41f))
        assertEquals(0f, resolveVisualTopScrollOffset(0f, 20f, 41f))
        assertEquals(0f, resolveVisualTopScrollOffset(0f, null, -1f))
        assertEquals(100f, resolveVisualTopScrollOffset(100f, 20f, null))
        assertEquals(Float.MAX_VALUE, resolveVisualTopScrollOffset(Float.MAX_VALUE, 20f, -1f))
    }
}
