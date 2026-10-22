package team.bjtuss.bjtuselfservice.windows

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsTrayPositionTest {
    @Test fun bottomRightTrayKeepsMenuAboveTheTaskbar() {
        assertEquals(
            Point(1740, 970),
            trayMenuLocation(Point(1900, 1050), Dimension(180, 70), Rectangle(0, 0, 1920, 1040)),
        )
    }

    @Test fun topTaskbarKeepsMenuBelowIt() {
        assertEquals(
            Point(500, 40),
            trayMenuLocation(Point(500, 20), Dimension(180, 70), Rectangle(0, 40, 1920, 1040)),
        )
    }

    @Test fun monitorToTheLeftPreservesNegativeCoordinates() {
        assertEquals(
            Point(-180, 930),
            trayMenuLocation(Point(-20, 1020), Dimension(180, 70), Rectangle(-1600, 0, 1600, 1000)),
        )
    }

    @Test fun scaledScreenUsesLogicalWorkAreaWithoutScalingThePointerAgain() {
        assertEquals(
            Point(1100, 770),
            trayMenuLocation(Point(1260, 835), Dimension(180, 70), Rectangle(0, 0, 1280, 840)),
        )
    }
}
