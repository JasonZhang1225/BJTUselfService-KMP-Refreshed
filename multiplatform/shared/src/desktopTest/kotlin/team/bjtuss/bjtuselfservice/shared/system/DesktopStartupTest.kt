package team.bjtuss.bjtuselfservice.shared.system

import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStartupTest {
    private val home: File = Files.createTempDirectory("startup-home").toFile()
    private val prefsNode = "/team/bjtuss/bjtuselfservice/kmp-test/startup-${System.nanoTime()}"

    @AfterTest fun cleanup() {
        home.deleteRecursively()
        runCatching { Preferences.userRoot().node(prefsNode).removeNode() }
    }

    @Test fun onlyLoginLaunchWithSilentStartAndAWayBackStartsHidden() {
        assertTrue(shouldStartHidden(arrayOf(BACKGROUND_LAUNCH_ARGUMENT), silentStart = true, canReopen = true))
        assertFalse(shouldStartHidden(emptyArray(), silentStart = true, canReopen = true), "手动打开总是显示窗口")
        assertFalse(shouldStartHidden(arrayOf(BACKGROUND_LAUNCH_ARGUMENT), silentStart = false, canReopen = true))
        assertFalse(shouldStartHidden(arrayOf(BACKGROUND_LAUNCH_ARGUMENT), silentStart = true, canReopen = false), "没有托盘/程序坞时不能藏起来")
    }

    @Test fun silentStartDefaultsOnAndPersists() {
        val prefs = DesktopStartupPreferences(prefsNode)
        assertTrue(prefs.silentStart())
        prefs.setSilentStart(false)
        assertFalse(DesktopStartupPreferences(prefsNode).silentStart())
    }

    @Test fun macLoginItemIsAUserLaunchAgentThatCanBeRemoved() {
        val executable = File(home, "Applications/交大自由行 KMP.app/Contents/MacOS/BJTUselfServiceKMP").apply {
            parentFile.mkdirs(); writeText("")
        }
        val host = MacLaunchAgentStartupHost(home, executable.absolutePath, DesktopStartupPreferences(prefsNode)) { true }
        assertTrue(host.autostartSupported)
        assertFalse(host.isAutostartEnabled())
        assertTrue(host.setAutostart(true))
        val plist = File(home, "Library/LaunchAgents/${MacLaunchAgentStartupHost.LABEL}.plist").readText()
        assertTrue("<string>/usr/bin/open</string>" in plist)
        assertTrue("交大自由行 KMP.app</string>" in plist)
        assertTrue("<string>$BACKGROUND_LAUNCH_ARGUMENT</string>" in plist)
        assertTrue(host.isAutostartEnabled())
        assertTrue(host.setAutostart(false))
        assertFalse(File(home, "Library/LaunchAgents/${MacLaunchAgentStartupHost.LABEL}.plist").exists())
    }

    @Test fun sourceRunCannotRegisterLoginItem() {
        val host = MacLaunchAgentStartupHost(home, null, DesktopStartupPreferences(prefsNode)) { true }
        assertFalse(host.autostartSupported)
        assertFalse(host.setAutostart(true))
        assertFalse(File(home, "Library/LaunchAgents").exists())
    }
}
