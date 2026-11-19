package team.bjtuss.bjtuselfservice.shared.cleanup

import java.nio.file.Files
import kotlin.test.*

class AccountCleanupDesktopRecoveryTest {
    @Test fun persistedOwnershipTokenDeletesOnlyTheStagingFile() {
        val root = Files.createTempDirectory("cleanup-staging-fixture-").toFile().canonicalFile
        try {
            val export = java.io.File(root, "export.mp4").apply { writeText("user-export") }
            val temporary = Files.createTempFile(root.toPath(), ".bjtu-save-", ".tmp")
            val token = desktopPlaybackTemporaryToken(export, temporary)
            clearDesktopPlaybackTemporaryDestination(token)
            clearDesktopPlaybackTemporaryDestination(token)
            assertFalse(Files.exists(temporary))
            assertEquals("user-export", export.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun aCompletedRenameNeverDeletesTheUserExport() {
        val root = Files.createTempDirectory("cleanup-renamed-fixture-").toFile().canonicalFile
        try {
            val export = java.io.File(root, "export.mp4")
            val temporary = Files.createTempFile(root.toPath(), ".bjtu-save-", ".tmp")
            Files.writeString(temporary, "completed-export")
            val token = desktopPlaybackTemporaryToken(export, temporary)
            Files.move(temporary, export.toPath())
            clearDesktopPlaybackTemporaryDestination(token)
            assertEquals("completed-export", export.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun unrelatedFileOrDifferentParentCannotBecomeADeletionTarget() {
        val root = Files.createTempDirectory("cleanup-ownership-fixture-").toFile().canonicalFile
        try {
            val export = java.io.File(root, "export.mp4").apply { writeText("user-export") }
            val unrelated = java.io.File(root, "notes.txt").apply { writeText("keep") }
            assertFails { clearDesktopPlaybackTemporaryDestination(desktopPlaybackTemporaryToken(export, unrelated.toPath())) }
            val other = java.io.File(root, "other").apply { mkdirs() }
            val temporary = Files.createTempFile(other.toPath(), ".bjtu-save-", ".tmp")
            assertFails { clearDesktopPlaybackTemporaryDestination(desktopPlaybackTemporaryToken(export, temporary)) }
            assertTrue(Files.exists(temporary))
            assertEquals("keep", unrelated.readText())
            assertEquals("user-export", export.readText())
        } finally { root.deleteRecursively() }
    }
}
