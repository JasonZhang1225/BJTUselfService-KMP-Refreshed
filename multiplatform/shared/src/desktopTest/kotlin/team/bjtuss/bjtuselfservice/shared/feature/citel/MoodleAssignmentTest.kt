package team.bjtuss.bjtuselfservice.shared.feature.citel

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.data.moodle.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.*

class MoodleAssignmentTest {
    private val file = HomeworkFileContent("new.pdf", "application/pdf", byteArrayOf(1, 2, 3))

    @Test fun citelAddsAndRemovesFilesAndSaveIsAlreadySubmitted() = runBlocking {
        val fixture = Fixture(finalRequired = false)
        val status = fixture.client.saveFiles(7, listOf(file), setOf("old.pdf"))
        assertTrue(status.submitted)
        assertTrue(status.editable)
        assertFalse(status.canFinalize)
        assertEquals(setOf("keep.pdf", "new.pdf"), status.files.map { it.name }.toSet())
        assertEquals(listOf("delete", "upload", "savesubmission"), fixture.actions)
        assertEquals("5", fixture.uploadRepository)
        assertEquals("85303", fixture.uploadContext)
    }
    @Test fun physicsSaveIsDraftUntilExplicitFinalizationAndThenLocks() = runBlocking {
        val fixture = Fixture(finalRequired = true)
        val saved = fixture.client.saveFiles(7, listOf(file))
        assertFalse(saved.submitted)
        assertTrue(saved.canFinalize)
        assertEquals(listOf("upload", "savesubmission"), fixture.actions)
        val finalized = fixture.client.finalize(7)
        assertTrue(finalized.submitted)
        assertFalse(finalized.editable)
        assertFalse(finalized.canFinalize)
        assertEquals("confirmsubmit", fixture.actions.last())
        assertFailsWith<MoodleAssignmentFailure> { fixture.client.saveFiles(7, listOf(file)) }
        Unit
    }
    @Test fun deletionOnlyKeepsRemainingFilesAndNeverFinalizes() = runBlocking {
        val fixture = Fixture(finalRequired = false)
        val after = fixture.client.saveFiles(7, emptyList(), setOf("old.pdf"))
        assertEquals(listOf("keep.pdf"), after.files.map { it.name })
        assertEquals(listOf("delete", "savesubmission"), fixture.actions)
    }
    @Test fun sameNamedFileIsReplacedInDraftAndExistingOtherFilesArePreserved() = runBlocking {
        val fixture = Fixture(finalRequired = false)
        fixture.client.saveFiles(7, listOf(file, HomeworkFileContent("old.pdf", "application/pdf", byteArrayOf(8))))
        assertEquals(setOf("old.pdf", "keep.pdf", "new.pdf"), fixture.saved)
        assertEquals(1, fixture.actions.count { it == "delete" })
    }
    @Test fun limitsAndAcceptedExtensionsFailBeforeAnyWrite() = runBlocking {
        val fixture = Fixture(finalRequired = true)
        assertFailsWith<MoodleAssignmentFailure> { fixture.client.saveFiles(7, listOf(HomeworkFileContent("wrong.txt", "text/plain", byteArrayOf(1)))) }
        assertFailsWith<MoodleAssignmentFailure> { fixture.client.saveFiles(7, listOf(HomeworkFileContent("big.pdf", "application/pdf", ByteArray(11)))) }
        assertTrue(fixture.actions.isEmpty())
    }
    @Test fun unknownExistingFileCannotBeRemoved() = runBlocking {
        val fixture = Fixture(finalRequired = false)
        assertFailsWith<MoodleAssignmentFailure> { fixture.client.saveFiles(7, emptyList(), setOf("missing.pdf")) }
        assertTrue(fixture.actions.isEmpty())
    }
    @Test fun lostResponseDoesNotRepeatSaveOrFinalSubmission() = runBlocking {
        val fixture = Fixture(finalRequired = false)
        fixture.disconnectOnSave = true
        assertFailsWith<IllegalStateException> { fixture.client.saveFiles(7, listOf(file)) }
        assertEquals(1, fixture.actions.count { it == "savesubmission" })
        assertTrue("new.pdf" in fixture.client.status(7).files.map { it.name })
    }
    @Test fun finalizationRequiresUserToAcceptTheDisplayedStatement() = runBlocking {
        val fixture = Fixture(finalRequired = true, statement = "I confirm this is my work.")
        fixture.client.saveFiles(7, listOf(file))
        val statement = fixture.client.finalizationStatement(7)
        assertEquals("I confirm this is my work.", statement)
        assertFailsWith<MoodleAssignmentFailure> { fixture.client.finalize(7) }
        assertFalse("confirmsubmit" in fixture.actions)
        assertTrue(fixture.client.finalize(7, statement).submitted)
    }
    @Test fun readStatusNeverTreatsDraftFilesAsSubmitted() {
        val status = parseMoodleAssignmentStatus("""<main id="region-main"><table class="submissionstatustable"><tr><th>作业状态</th><td>草稿（未提交）</td></tr><tr><td><a href="/pluginfile.php/file">old.pdf</a></td></tr></table><form><input name="action" value="submit"></form></main>""")
        assertFalse(status.submitted)
        assertTrue(status.canFinalize)
        assertEquals(listOf("old.pdf"), status.files.map { it.name })
    }

    private class Fixture(val finalRequired: Boolean, val statement: String? = null) {
        val base = "https://citel.bjtu.edu.cn/mlsv3"
        var saved = linkedSetOf("old.pdf", "keep.pdf")
        var draft = saved.toMutableSet()
        var draftSaved = false
        var locked = false
        var disconnectOnSave = false
        val actions = mutableListOf<String>()
        var uploadRepository: String? = null
        var uploadContext: String? = null
        val client = MoodleAssignmentClient(base, read = { read(it) }, write = { write(it) })
        fun response(url: String, body: String) = SchoolHttpResponse(200, url, body = body.encodeToByteArray())
        fun main(): String = """<main id="region-main"><table class="submissionstatustable"><tr><th>Submission status</th><td>${if (finalRequired && !locked) "Draft (not submitted)" else "Submitted for grading"}</td></tr><tr><td>${saved.joinToString("") { """<a href="$base/pluginfile.php/$it">$it</a>""" }}</td></tr></table>${if (!locked) """<form><input name="action" value="editsubmission"></form>""" else ""}${if (finalRequired && draftSaved && !locked) """<form><input name="action" value="submit"></form>""" else ""}</main>"""
        suspend fun read(url: String): SchoolHttpResponse {
            if (url.endsWith("action=editsubmission")) {
                draft = saved.toMutableSet()
                val html = """<main id="region-main"><form action="$base/mod/assign/view.php"><input type="hidden" name="id" value="7"><input type="hidden" name="action" value="savesubmission"><input type="hidden" name="sesskey" value="testkey"><input type="hidden" name="files_filemanager" value="123"></form></main><script>M.form_filemanager.init(Y,{"itemid":123,"client_id":"testclient","context":{"id":85303},"maxfiles":20,"maxbytes":10,"accepted_types":[".pdf"],"list":[${saved.joinToString(",") { """{"filename":"$it","fullname":"$it","filepath":"/"}""" }}],"filepicker":{"repositories":{"4":{"type":"recent"},"5":{"type":"upload"}}}});</script>"""
                return response(url, html)
            }
            if (url.endsWith("action=submit")) return response(url,
                """<main id="region-main"><form action="$base/mod/assign/view.php"><input type="hidden" name="id" value="7"><input type="hidden" name="action" value="confirmsubmit"><input type="hidden" name="sesskey" value="testkey">${statement?.let { """<label><input type="checkbox" name="submissionstatement" value="1">$it</label>""" } ?: ""}<input type="submit" name="submitbutton" value="Continue"></form></main>""")
            return response(url, main())
        }
        suspend fun write(request: SchoolHttpRequest): SchoolHttpResponse {
            val action = request.formFields["action"] ?: "delete"
            actions += action
            when (action) {
                "delete" -> draft.remove(request.formFields.getValue("filename"))
                "upload" -> {
                    uploadRepository = request.formFields["repo_id"]
                    uploadContext = request.formFields["ctx_id"]
                    draft += request.multipartFiles.single().fileName
                }
                "savesubmission" -> { saved = LinkedHashSet(draft); draftSaved = true; if (disconnectOnSave) error("fixture lost response") }
                "confirmsubmit" -> locked = true
            }
            return response(request.url, if (action in listOf("upload", "delete")) "{}" else main())
        }
    }
}
