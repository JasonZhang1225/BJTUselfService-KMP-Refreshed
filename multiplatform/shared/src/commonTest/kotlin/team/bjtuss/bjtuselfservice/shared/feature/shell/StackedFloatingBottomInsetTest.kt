package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class StackedFloatingBottomInsetTest {
    @Test
    fun stacksBarHeightAndSystemSafeArea() {
        assertEquals(114.dp, stackedFloatingBottomInset(80.dp, 34.dp))
    }

    @Test
    fun pushedPageKeepsOnlyTheSystemSafeArea() {
        assertEquals(34.dp, stackedFloatingBottomInset(0.dp, 34.dp))
    }

    @Test
    fun ignoresNegativeInsets() {
        assertEquals(80.dp, stackedFloatingBottomInset(80.dp, (-8).dp))
        assertEquals(0.dp, stackedFloatingBottomInset((-4).dp, (-8).dp))
    }
}
