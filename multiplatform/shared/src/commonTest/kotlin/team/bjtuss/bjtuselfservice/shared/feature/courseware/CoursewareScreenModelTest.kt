package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareOperationResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNodeKind
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult

class CoursewareScreenModelTest {
    @Test
    fun initializationAndCompactNavigationPreserveFolderPath() = runBlocking {
        val repository = FakeRepository(snapshot(), snapshot())
        val model = CoursewareScreenModel(repository)

        model.initialize()
        model.openCompactNode(folder().stableKey)

        assertEquals(CoursewareContentSource.NETWORK, model.state.value.source)
        assertEquals(listOf("第一章"), model.state.value.compactPathNames)
        assertEquals(listOf("第一讲.pdf"), model.state.value.compactNodes.map { it.name })
        assertTrue(model.navigateCompactBack())
        assertFalse(model.navigateCompactBack())
    }

    @Test
    fun refreshReloadsTopLevelResourcesWithoutReSelectingTheCourse() = runBlocking {
        val repository = FakeRepository(
            loaded = snapshot("旧课件"),
            refreshed = snapshot("旧课件"),
            rootSnapshot = snapshot("新课件"),
        )
        val model = CoursewareScreenModel(repository)

        model.initialize()

        assertEquals(
            "新课件",
            model.state.value.selectedCourse?.children?.first()?.children?.single()?.name,
        )
        assertEquals(1, repository.rootLoadCount)
    }

    @Test
    fun openingUnloadedFolderFetchesAndPersistsItsDirectChildrenFirst() = runBlocking {
        val repository = FakeRepository(
            loaded = partialSnapshot(),
            refreshed = partialSnapshot(),
            loadedFolderSnapshot = snapshot(),
        )
        val model = CoursewareScreenModel(repository)

        model.initialize()
        model.openCompactNode(folder().stableKey)

        assertEquals(1, repository.folderLoadCount)
        assertEquals(listOf("第一讲.pdf"), model.state.value.compactNodes.map { it.name })
        assertFalse(model.state.value.loadingFolderKeys.contains(folder().stableKey))
    }

    @Test
    fun completeExportHydratesUnloadedFoldersBeforeOpeningDestination() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakeRepository(
            loaded = partialSnapshot(),
            refreshed = partialSnapshot(),
            events = events,
            loadedFolderSnapshot = snapshot(),
        )
        val gateway = RecordingDirectoryGateway(events)
        val model = CoursewareScreenModel(repository)
        model.initialize()

        val result = model.exportDirectory(null, "程序设计", gateway)

        assertEquals(HomeworkFileSaveResult.Saved, assertIs<CoursewareOperationResult.Success<HomeworkFileSaveResult>>(result).value)
        assertEquals("load-folder:17:FOLDER:1", events.first())
        assertEquals("open:程序设计", events[1])
        assertEquals(2, gateway.files.size)
    }

    @Test
    fun expandedTreeSelectionAndResourceEnumerationAreRecursive() = runBlocking {
        val model = CoursewareScreenModel(FakeRepository(snapshot(), snapshot()))
        model.initialize()

        model.toggleExpanded(folder().stableKey)
        model.selectNode(resource().stableKey)

        assertEquals(listOf("第一章", "第一讲.pdf", "说明.pdf"), model.state.value.visibleTree.map { it.node.name })
        assertEquals("第一讲.pdf", model.state.value.selectedNode?.name)
        assertEquals(listOf("第一讲.pdf"), model.resourcesUnder(folder().stableKey).map { it.name })
    }

    @Test
    fun downloadUsesTypedOperationAndClearsBusyState() = runBlocking {
        val file = HomeworkFileContent("第一讲.pdf", "application/pdf", "body".encodeToByteArray())
        val model = CoursewareScreenModel(FakeRepository(snapshot(), snapshot(), file))
        model.initialize()

        val result = model.downloadResource(resource().stableKey)

        assertEquals(file, assertIs<CoursewareOperationResult.Success<HomeworkFileContent>>(result).value)
        assertFalse(model.state.value.isDownloading)
    }

    @Test
    fun resourceDownloadRetriesTemporaryInvalidResponse() = runBlocking {
        var calls = 0
        val file = HomeworkFileContent("第一讲.pdf", "application/pdf", "body".encodeToByteArray())
        val repository = object : CoursewareRepository {
            override fun load(): CoursewareSnapshot = snapshot()
            override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(snapshot())
            override suspend fun loadCoursesConcurrently(
                snapshot: CoursewareSnapshot,
                courseIds: List<Int>,
                concurrency: Int,
            ): CoursewareOperationResult<CoursewareSnapshot> = CoursewareOperationResult.Success(snapshot())
            override suspend fun downloadResource(
                node: CoursewareNode,
            ): CoursewareOperationResult<HomeworkFileContent> {
                calls++
                return if (calls == 1) {
                    CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
                } else {
                    CoursewareOperationResult.Success(file)
                }
            }
            override suspend fun downloadTeachingCalendar(
                course: CoursewareCourse,
            ): CoursewareOperationResult<HomeworkFileContent> = CoursewareOperationResult.Success(file)
        }
        val model = CoursewareScreenModel(repository)
        model.initialize()

        val result = model.downloadResource(resource().stableKey)

        assertEquals(file, assertIs<CoursewareOperationResult.Success<HomeworkFileContent>>(result).value)
        assertEquals(2, calls)
        assertFalse(model.state.value.isDownloading)
    }

    @Test
    fun resourceDownloadReauthenticatesOnceAfterSessionExpiry() = runBlocking {
        var calls = 0
        var recoveryCalls = 0
        val file = HomeworkFileContent("第一讲.pdf", "application/pdf", "body".encodeToByteArray())
        val repository = object : CoursewareRepository {
            override fun load(): CoursewareSnapshot = snapshot()
            override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(snapshot())
            override suspend fun loadCoursesConcurrently(
                snapshot: CoursewareSnapshot,
                courseIds: List<Int>,
                concurrency: Int,
            ): CoursewareOperationResult<CoursewareSnapshot> = CoursewareOperationResult.Success(snapshot())
            override suspend fun downloadResource(
                node: CoursewareNode,
            ): CoursewareOperationResult<HomeworkFileContent> {
                calls++
                return if (calls == 1) {
                    CoursewareOperationResult.Failure(CoursewareSyncFailure.SESSION_EXPIRED)
                } else {
                    CoursewareOperationResult.Success(file)
                }
            }
            override suspend fun downloadTeachingCalendar(
                course: CoursewareCourse,
            ): CoursewareOperationResult<HomeworkFileContent> = CoursewareOperationResult.Success(file)
        }
        val model = CoursewareScreenModel(
            repository = repository,
            reauthenticate = {
                recoveryCalls++
                true
            },
        )
        model.initialize()

        val result = model.downloadResource(resource().stableKey)

        assertIs<CoursewareOperationResult.Success<HomeworkFileContent>>(result)
        assertEquals(2, calls)
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun directoryExportOpensDestinationBeforeDownloadingAndWritesOneFileAtATime() = runBlocking {
        val courseEvents = mutableListOf<String>()
        val courseGateway = RecordingDirectoryGateway(courseEvents)
        val model = CoursewareScreenModel(FakeRepository(snapshot(), snapshot(), events = courseEvents))
        model.initialize()

        val courseResult = assertIs<CoursewareOperationResult.Success<HomeworkFileSaveResult>>(
            model.exportDirectory(null, "程序设计", courseGateway),
        ).value
        val folderEvents = mutableListOf<String>()
        val folderGateway = RecordingDirectoryGateway(folderEvents)
        val folderModel = CoursewareScreenModel(FakeRepository(snapshot(), snapshot(), events = folderEvents))
        folderModel.initialize()
        val folderResult = assertIs<CoursewareOperationResult.Success<HomeworkFileSaveResult>>(
            folderModel.exportDirectory(folder().stableKey, "第一章", folderGateway),
        ).value

        assertEquals(HomeworkFileSaveResult.Saved, courseResult)
        assertEquals("open:程序设计", courseEvents.first())
        assertEquals(
            listOf("open:程序设计", "download:2", "write:第一章", "download:3", "write:", "commit"),
            courseEvents,
        )
        assertEquals(listOf(listOf("第一章"), emptyList()), courseGateway.files.map { it.relativeFolders })
        assertTrue(courseGateway.committed)
        assertFalse(courseGateway.aborted)
        assertEquals(HomeworkFileSaveResult.Saved, folderResult)
        assertEquals(listOf(emptyList()), folderGateway.files.map { it.relativeFolders })
        assertEquals("file.bin", folderGateway.files.single().content.fileName)
        assertEquals(0, model.state.value.directoryDownloadTotal)
        assertFalse(model.state.value.isDownloading)
    }

    @Test
    fun directoryExportAbortsNewRootWhenALaterDownloadFails() = runBlocking {
        var calls = 0
        val repository = object : CoursewareRepository {
            override fun load(): CoursewareSnapshot = snapshot()
            override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(snapshot())
            override suspend fun loadCourse(
                snapshot: CoursewareSnapshot,
                courseId: Int,
            ): CoursewareOperationResult<CoursewareSnapshot> = CoursewareOperationResult.Success(snapshot())
            override suspend fun downloadResource(
                node: CoursewareNode,
            ): CoursewareOperationResult<HomeworkFileContent> {
                calls++
                return if (calls == 1) {
                    CoursewareOperationResult.Success(
                        HomeworkFileContent(node.name, "application/pdf", byteArrayOf(1)),
                    )
                } else {
                    CoursewareOperationResult.Failure(team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure.NETWORK)
                }
            }
            override suspend fun downloadTeachingCalendar(
                course: CoursewareCourse,
            ): CoursewareOperationResult<HomeworkFileContent> = error("Not used")
        }
        val gateway = RecordingDirectoryGateway(mutableListOf())
        val model = CoursewareScreenModel(repository)
        model.initialize()

        val result = model.exportDirectory(null, "程序设计", gateway)

        assertIs<CoursewareOperationResult.Failure>(result)
        assertEquals(1, gateway.files.size)
        assertFalse(gateway.committed)
        assertTrue(gateway.aborted)
        assertFalse(model.state.value.isDownloading)
    }

    @Test
    fun directoryExportCancellationAbortsTheOpenSession() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        var aborted = false
        val gateway = object : CoursewareDirectoryGateway {
            override val isDirectoryExportAvailable: Boolean = true

            override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult =
                CoursewareDirectoryOpenResult.Opened(
                    object : CoursewareDirectoryWriteSession {
                        override suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult {
                            writeStarted.complete(Unit)
                            awaitCancellation()
                        }

                        override suspend fun commit(): HomeworkFileSaveResult = HomeworkFileSaveResult.Saved

                        override suspend fun abort() {
                            aborted = true
                        }
                    },
                )
        }
        val model = CoursewareScreenModel(FakeRepository(snapshot(), snapshot()))
        model.initialize()

        val export = async { model.exportDirectory(null, "程序设计", gateway) }
        writeStarted.await()
        export.cancel()
        export.join()

        assertTrue(export.isCancelled)
        assertTrue(aborted)
        assertFalse(model.state.value.isDownloading)
    }

    @Test
    fun networkOperationsAreSerializedAcrossResourceAndCalendarDownloads() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        suspend fun gatedFile(fileName: String): CoursewareOperationResult<HomeworkFileContent> {
            active++
            maxActive = maxOf(maxActive, active)
            if (!firstStarted.isCompleted) firstStarted.complete(Unit)
            releaseFirst.await()
            active--
            return CoursewareOperationResult.Success(
                HomeworkFileContent(fileName, "application/pdf", "%PDF".encodeToByteArray()),
            )
        }
        val repository = object : CoursewareRepository {
            override fun load(): CoursewareSnapshot = snapshot()
            override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(snapshot())
            override suspend fun loadCoursesConcurrently(
                snapshot: CoursewareSnapshot,
                courseIds: List<Int>,
                concurrency: Int,
            ): CoursewareOperationResult<CoursewareSnapshot> = CoursewareOperationResult.Success(snapshot())
            override suspend fun downloadResource(
                node: CoursewareNode,
            ): CoursewareOperationResult<HomeworkFileContent> = gatedFile(node.name)
            override suspend fun downloadTeachingCalendar(
                course: CoursewareCourse,
            ): CoursewareOperationResult<HomeworkFileContent> = gatedFile("calendar.pdf")
        }
        val model = CoursewareScreenModel(repository)
        model.initialize()

        val resourceDownload = async { model.downloadResource(resource().stableKey) }
        firstStarted.await()
        val calendarDownload = async { model.downloadTeachingCalendar() }
        yield()

        assertEquals(1, active)
        assertEquals(1, maxActive)
        releaseFirst.complete(Unit)
        resourceDownload.await()
        calendarDownload.await()
        assertEquals(1, maxActive)
        assertFalse(model.state.value.isDownloading)
    }

    private class FakeRepository(
        private val loaded: CoursewareSnapshot,
        private val refreshed: CoursewareSnapshot,
        private val file: HomeworkFileContent = HomeworkFileContent("file.bin", "application/octet-stream", byteArrayOf(1)),
        private val events: MutableList<String>? = null,
        private val loadedFolderSnapshot: CoursewareSnapshot? = null,
        private val rootSnapshot: CoursewareSnapshot = refreshed,
    ) : CoursewareRepository {
        var courseLoadCount = 0
        var folderLoadCount = 0
        var rootLoadCount = 0
        override fun load(): CoursewareSnapshot = loaded
        override suspend fun refresh(): CoursewareRefreshResult = CoursewareRefreshResult.Success(refreshed)
        override suspend fun loadCourse(
            snapshot: CoursewareSnapshot,
            courseId: Int,
        ): CoursewareOperationResult<CoursewareSnapshot> {
            courseLoadCount += 1
            return CoursewareOperationResult.Success(refreshed)
        }
        override suspend fun loadCoursesConcurrently(
            snapshot: CoursewareSnapshot,
            courseIds: List<Int>,
            concurrency: Int,
        ): CoursewareOperationResult<CoursewareSnapshot> {
            rootLoadCount++
            return CoursewareOperationResult.Success(rootSnapshot)
        }
        override suspend fun loadFolder(
            snapshot: CoursewareSnapshot,
            courseId: Int,
            folderKey: String,
        ): CoursewareOperationResult<CoursewareSnapshot> {
            folderLoadCount += 1
            events?.add("load-folder:$folderKey")
            return loadedFolderSnapshot?.let { CoursewareOperationResult.Success(it) }
                ?: CoursewareOperationResult.Failure(
                    team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure.MALFORMED_RESPONSE,
                )
        }
        override suspend fun downloadResource(node: CoursewareNode): CoursewareOperationResult<HomeworkFileContent> {
            events?.add("download:${node.id}")
            return CoursewareOperationResult.Success(file)
        }

        override suspend fun downloadTeachingCalendar(
            course: CoursewareCourse,
        ): CoursewareOperationResult<HomeworkFileContent> = CoursewareOperationResult.Success(
            HomeworkFileContent("${course.name}_教学日历.pdf", "application/pdf", "%PDF".encodeToByteArray()),
        )
    }

    private class RecordingDirectoryGateway(
        private val events: MutableList<String>,
    ) : CoursewareDirectoryGateway {
        val files = mutableListOf<CoursewareDirectoryFile>()
        var committed = false
        var aborted = false

        override val isDirectoryExportAvailable: Boolean = true

        override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult {
            events += "open:$directoryName"
            return CoursewareDirectoryOpenResult.Opened(
                object : CoursewareDirectoryWriteSession {
                    override suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult {
                        events += "write:${file.relativeFolders.joinToString("/")}"
                        files += file
                        return HomeworkFileSaveResult.Saved
                    }

                    override suspend fun commit(): HomeworkFileSaveResult {
                        events += "commit"
                        committed = true
                        return HomeworkFileSaveResult.Saved
                    }

                    override suspend fun abort() {
                        events += "abort"
                        aborted = true
                    }
                },
            )
        }
    }

    private fun snapshot(resourceName: String = "第一讲.pdf") = CoursewareSnapshot(
        listOf(
            CoursewareCourse(
                id = 17,
                name = "程序设计",
                courseNumber = "CS101",
                groupId = "G1",
                semesterCode = "2026-1",
                teacherId = 28,
                children = listOf(folder(resourceName), description()),
            ),
        ),
    )

    private fun partialSnapshot() = snapshot().let { full ->
        full.copy(
            courses = full.courses.map { course ->
                course.copy(
                    children = course.children.map { node ->
                        if (node.isFolder) node.copy(children = emptyList(), childrenLoaded = false) else node
                    },
                )
            },
        )
    }

    private fun folder(resourceName: String = "第一讲.pdf") = CoursewareNode(
        id = 1,
        courseId = 17,
        name = "第一章",
        kind = CoursewareNodeKind.FOLDER,
        children = listOf(resource(resourceName)),
    )

    private fun resource(name: String = "第一讲.pdf") = CoursewareNode(
        id = 2,
        courseId = 17,
        name = name,
        kind = CoursewareNodeKind.RESOURCE,
        rpId = "rp-2",
    )

    private fun description() = CoursewareNode(
        id = 3,
        courseId = 17,
        name = "说明.pdf",
        kind = CoursewareNodeKind.RESOURCE,
        rpId = "rp-3",
    )
}
