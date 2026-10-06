package team.bjtuss.bjtuselfservice.shared.data.courseware

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNodeKind
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

class CoursewareRepositoryTest {
    @Test
    fun refreshPreservesCachedTreeThenCourseLoadReplacesOnlyCurrentAccountSnapshot() = runBlocking {
        val local = FakeLocal(snapshot("旧课件"))
        val catalog = snapshot("unused").let { value ->
            value.copy(courses = value.courses.map { it.copy(children = emptyList(), childrenLoaded = false) })
        }
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(catalog, folderChildren = snapshot("新课件").courses.single().children),
        )

        val refreshed = assertIs<CoursewareRefreshResult.Success>(repository.refresh())
        assertEquals("旧课件", refreshed.snapshot.courses.single().children.single().name)
        val loaded = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadCourse(refreshed.snapshot, 17),
        )

        assertEquals("新课件", loaded.value.courses.single().children.single().name)
        assertEquals(listOf("student-a", "student-a"), local.replacedAccounts)
    }

    @Test
    fun remoteAndCacheFailurePreserveOldSnapshot() = runBlocking {
        val cached = snapshot("完整缓存")
        val remoteFailureLocal = FakeLocal(cached)
        val remoteFailure = DefaultCoursewareRepository(
            "student-a",
            remoteFailureLocal,
            FakeRemote(error = CoursewareRemoteException(CoursewareRemoteFailure.SESSION_EXPIRED)),
        )
        val first = assertIs<CoursewareRefreshResult.Failure>(remoteFailure.refresh())
        assertEquals(cached, first.snapshot)
        assertEquals(CoursewareSyncFailure.SESSION_EXPIRED, first.reason)
        assertTrue(remoteFailureLocal.replacedAccounts.isEmpty())

        val cacheFailure = DefaultCoursewareRepository(
            "student-a",
            FakeLocal(cached, failReplace = true),
            FakeRemote(snapshot("新课件")),
        )
        val second = assertIs<CoursewareRefreshResult.Failure>(cacheFailure.refresh())
        assertEquals(cached, second.snapshot)
        assertEquals(CoursewareSyncFailure.CACHE, second.reason)
    }

    @Test
    fun loadingFolderMergesDirectChildrenAndReplacesCachedSnapshot() = runBlocking {
        val unloadedFolder = CoursewareNode(
            id = 1,
            courseId = 17,
            name = "第一章",
            kind = CoursewareNodeKind.FOLDER,
            childrenLoaded = false,
        )
        val partial = snapshot("说明.pdf").let { current ->
            current.copy(courses = current.courses.map { it.copy(children = listOf(unloadedFolder)) })
        }
        val child = CoursewareNode(
            id = 2,
            courseId = 17,
            name = "第一讲.pdf",
            kind = CoursewareNodeKind.RESOURCE,
            rpId = "rp-2",
        )
        val local = FakeLocal(partial)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = partial, folderChildren = listOf(child)),
        )

        val result = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadFolder(partial, 17, unloadedFolder.stableKey),
        )

        val loaded = result.value.courses.single().children.single()
        assertTrue(loaded.childrenLoaded)
        assertEquals(listOf(child), loaded.children)
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    @Test
    fun loadCourseKeepsAlreadyLoadedFolderChildrenWhenTopLevelRefreshes() = runBlocking {
        val folder = CoursewareNode(
            id = 1,
            courseId = 17,
            name = "第一章",
            kind = CoursewareNodeKind.FOLDER,
            children = listOf(
                CoursewareNode(
                    id = 2,
                    courseId = 17,
                    name = "旧讲义.pdf",
                    kind = CoursewareNodeKind.RESOURCE,
                    rpId = "rp-2",
                ),
            ),
            childrenLoaded = true,
        )
        val cached = CoursewareSnapshot(
            listOf(course(children = listOf(folder), childrenLoaded = true)),
        )
        val incomingFolder = folder.copy(children = emptyList(), childrenLoaded = false)
        val extra = CoursewareNode(
            id = 9,
            courseId = 17,
            name = "新课件.pdf",
            kind = CoursewareNodeKind.RESOURCE,
            rpId = "rp-9",
        )
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = cached, folderChildren = listOf(incomingFolder, extra)),
        )

        val loaded = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadCourse(cached, 17),
        )
        val children = loaded.value.courses.single().children
        assertEquals(listOf("第一章", "新课件.pdf"), children.map { it.name })
        val mergedFolder = children.first { it.isFolder }
        assertFalse(mergedFolder.childrenLoaded)
        assertEquals(listOf("旧讲义.pdf"), mergedFolder.children.map { it.name })
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    @Test
    fun refreshPrefetchReplacesCachedFolderChildrenWithRemoteUpdates() = runBlocking {
        val folder = CoursewareNode(
            id = 1,
            courseId = 17,
            name = "第一章",
            kind = CoursewareNodeKind.FOLDER,
            children = listOf(
                CoursewareNode(
                    id = 2,
                    courseId = 17,
                    name = "旧讲义.pdf",
                    kind = CoursewareNodeKind.RESOURCE,
                    rpId = "rp-2",
                ),
            ),
            childrenLoaded = true,
        )
        val cached = CoursewareSnapshot(
            listOf(course(children = listOf(folder), childrenLoaded = true)),
        )
        val incomingFolder = folder.copy(children = emptyList(), childrenLoaded = false)
        val updated = CoursewareNode(
            id = 4,
            courseId = 17,
            name = "新讲义.pdf",
            kind = CoursewareNodeKind.RESOURCE,
            rpId = "rp-4",
        )
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(
                snapshot = cached,
                childrenByParentId = mapOf(
                    0 to listOf(incomingFolder),
                    1 to listOf(updated),
                ),
            ),
        )

        val top = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadCourse(cached, 17),
        )
        val prefetched = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadUnloadedFolders(top.value, concurrency = 2),
        )
        val loadedFolder = prefetched.value.courses.single().children.single()
        assertTrue(loadedFolder.childrenLoaded)
        assertEquals(listOf("新讲义.pdf"), loadedFolder.children.map { it.name })
    }

    @Test
    fun emptyTopLevelReplacesNonEmptyCourse() = runBlocking {
        val cached = snapshot("旧课件")
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = cached, folderChildren = emptyList()),
        )

        val result = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadCourse(cached, 17),
        )
        val loaded = result.value.courses.single()
        assertTrue(loaded.childrenLoaded)
        assertTrue(loaded.children.isEmpty())
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    @Test
    fun emptyFolderPrefetchClearsCachedChildren() = runBlocking {
        val folder = CoursewareNode(
            id = 1,
            courseId = 17,
            name = "第一章",
            kind = CoursewareNodeKind.FOLDER,
            children = listOf(
                CoursewareNode(
                    id = 2,
                    courseId = 17,
                    name = "旧讲义.pdf",
                    kind = CoursewareNodeKind.RESOURCE,
                    rpId = "rp-2",
                ),
            ),
            childrenLoaded = false,
        )
        val cached = CoursewareSnapshot(
            listOf(course(children = listOf(folder), childrenLoaded = true)),
        )
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = cached, folderChildren = emptyList()),
        )

        val result = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadUnloadedFolders(cached, concurrency = 2),
        )
        val loadedFolder = result.value.courses.single().children.single()
        assertTrue(loadedFolder.childrenLoaded)
        assertTrue(loadedFolder.children.isEmpty())
    }

    @Test
    fun concurrentCourseLoadsKeepBothUpdates() = runBlocking {
        val cached = CoursewareSnapshot(
            listOf(
                course(
                    id = 17,
                    children = listOf(resourceNode(17, 2, "旧A.pdf")),
                ),
                course(
                    id = 18,
                    name = "离散数学",
                    courseNumber = "CS102",
                    children = listOf(resourceNode(18, 2, "旧B.pdf")),
                ),
            ),
        )
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(
                snapshot = cached,
                childrenByCourseId = mapOf(
                    17 to listOf(resourceNode(17, 2, "新A.pdf")),
                    18 to listOf(resourceNode(18, 2, "新B.pdf")),
                ),
                delayMillisByCourseId = mapOf(17 to 40L),
            ),
        )

        val first = async { repository.loadCourse(cached, 17) }
        val second = async { repository.loadCourse(cached, 18) }
        assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(first.await())
        assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(second.await())

        val names = local.snapshot.courses.associate { it.id to it.children.single().name }
        assertEquals("新A.pdf", names[17])
        assertEquals("新B.pdf", names[18])
    }

    @Test
    fun emptyTopLevelMarksLoadedWhenCourseWasAlreadyEmpty() = runBlocking {
        val emptyCourse = course(children = emptyList(), childrenLoaded = false)
        val cached = CoursewareSnapshot(listOf(emptyCourse))
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = cached, folderChildren = emptyList()),
        )

        val result = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadCoursesConcurrently(cached, listOf(17), concurrency = 2),
        )
        val loaded = result.value.courses.single()
        assertTrue(loaded.childrenLoaded)
        assertTrue(loaded.children.isEmpty())
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    @Test
    fun emptyCatalogRefreshKeepsCachedSnapshot() = runBlocking {
        val cached = snapshot("完整缓存")
        val local = FakeLocal(cached)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = CoursewareSnapshot(emptyList())),
        )

        val result = assertIs<CoursewareRefreshResult.Failure>(repository.refresh())
        assertEquals(cached, result.snapshot)
        assertEquals(CoursewareSyncFailure.NETWORK, result.reason)
        assertTrue(local.replacedAccounts.isEmpty())
    }

    @Test
    fun prefetchLoadsUnloadedFoldersWithoutClearingTopLevel() = runBlocking {
        val unloadedFolder = CoursewareNode(
            id = 1,
            courseId = 17,
            name = "第一章",
            kind = CoursewareNodeKind.FOLDER,
            childrenLoaded = false,
        )
        val extra = CoursewareNode(
            id = 3,
            courseId = 17,
            name = "说明.pdf",
            kind = CoursewareNodeKind.RESOURCE,
            rpId = "rp-3",
        )
        val partial = CoursewareSnapshot(
            listOf(course(children = listOf(unloadedFolder, extra), childrenLoaded = true)),
        )
        val child = CoursewareNode(
            id = 2,
            courseId = 17,
            name = "第一讲.pdf",
            kind = CoursewareNodeKind.RESOURCE,
            rpId = "rp-2",
        )
        val local = FakeLocal(partial)
        val repository = DefaultCoursewareRepository(
            "student-a",
            local,
            FakeRemote(snapshot = partial, folderChildren = listOf(child)),
        )

        val result = assertIs<CoursewareOperationResult.Success<CoursewareSnapshot>>(
            repository.loadUnloadedFolders(partial, concurrency = 2),
        )
        val children = result.value.courses.single().children
        assertEquals(listOf("第一章", "说明.pdf"), children.map { it.name })
        assertTrue(children.first { it.isFolder }.childrenLoaded)
        assertEquals(listOf("第一讲.pdf"), children.first { it.isFolder }.children.map { it.name })
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    private class FakeLocal(
        var snapshot: CoursewareSnapshot,
        private val failReplace: Boolean = false,
    ) : CoursewareLocalDataSource {
        val replacedAccounts = mutableListOf<String>()

        override fun load(accountScope: String): CoursewareSnapshot = snapshot

        override fun replace(accountScope: String, snapshot: CoursewareSnapshot) {
            if (failReplace) error("synthetic courseware cache failure")
            replacedAccounts += accountScope
            this.snapshot = snapshot
        }
    }

    private class FakeRemote(
        private val snapshot: CoursewareSnapshot? = null,
        private val error: Exception? = null,
        private val folderChildren: List<CoursewareNode> = emptyList(),
        private val childrenByParentId: Map<Int, List<CoursewareNode>>? = null,
        private val childrenByCourseId: Map<Int, List<CoursewareNode>>? = null,
        private val delayMillisByCourseId: Map<Int, Long> = emptyMap(),
    ) : CoursewareRemoteDataSource {
        override suspend fun fetchSnapshot(): CoursewareSnapshot {
            error?.let { throw it }
            return requireNotNull(snapshot)
        }

        override suspend fun fetchChildren(
            course: CoursewareCourse,
            parentId: Int,
        ): List<CoursewareNode> {
            delayMillisByCourseId[course.id]?.let { delay(it) }
            childrenByCourseId?.get(course.id)?.let { return it }
            return childrenByParentId?.get(parentId) ?: folderChildren
        }

        override suspend fun downloadResource(node: CoursewareNode): HomeworkFileContent =
            HomeworkFileContent(node.name, "application/octet-stream", byteArrayOf(1))

        override suspend fun downloadTeachingCalendar(course: CoursewareCourse): HomeworkFileContent =
            HomeworkFileContent("${course.name}_教学日历.pdf", "application/pdf", "%PDF".encodeToByteArray())
    }

    private fun snapshot(name: String) = CoursewareSnapshot(
        listOf(
            course(
                children = listOf(
                    CoursewareNode(
                        id = 2,
                        courseId = 17,
                        name = name,
                        kind = CoursewareNodeKind.RESOURCE,
                        rpId = "rp-2",
                    ),
                ),
            ),
        ),
    )

    private fun course(
        children: List<CoursewareNode>,
        childrenLoaded: Boolean = true,
        id: Int = 17,
        name: String = "程序设计",
        courseNumber: String = "CS101",
    ) = CoursewareCourse(
        id = id,
        name = name,
        courseNumber = courseNumber,
        groupId = "G1",
        semesterCode = "2026-1",
        teacherId = 28,
        children = children,
        childrenLoaded = childrenLoaded,
    )

    private fun resourceNode(courseId: Int, id: Int, name: String) = CoursewareNode(
        id = id,
        courseId = courseId,
        name = name,
        kind = CoursewareNodeKind.RESOURCE,
        rpId = "rp-$id",
    )
}
