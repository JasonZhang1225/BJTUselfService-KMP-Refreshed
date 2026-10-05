package team.bjtuss.bjtuselfservice.shared.data.classroom

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class ClassroomRemoteDataSourceTest {
    @Test
    fun buildsEncodedRequestAndParsesResponse() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "http://yaya.csoci.com:2333/api/classnum/?building=%E6%80%9D%E6%BA%90%E6%A5%BC",
                body = """{"time":["a","b"],"data":[["SY101",0.0,0,90]]}""".encodeToByteArray(),
            ),
        )
        val info = SchoolClassroomRemoteDataSource(
            transport = transport,
            legacyHttpAvailable = true,
        ).fetchBuildingInfo("思源楼")
        assertEquals(1, info.classrooms.size)
        assertTrue(transport.requests.single().url.endsWith("building=%E6%80%9D%E6%BA%90%E6%A5%BC"))
    }

    @Test
    fun rejectsRedirectOutsideExactOrigin() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "http://example.com/api/classnum/",
                body = """{"time":["a","b"],"data":[]}""".encodeToByteArray(),
            ),
        )
        val error = assertFailsWith<ClassroomRemoteException> {
            SchoolClassroomRemoteDataSource(
                transport = transport,
                legacyHttpAvailable = true,
            ).fetchBuildingInfo("思源楼")
        }
        assertEquals(ClassroomRemoteFailure.NETWORK, error.reason)
        assertEquals(1, transport.requests.size)
        assertEquals(0, transport.redirectlessMisses)
    }

    @Test
    fun doesNotFollowCleartextRedirect() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 302,
                finalUrl = "http://yaya.csoci.com:2333/api/classnum/",
                headers = mapOf("Location" to listOf("http://evil.example/collect")),
            ),
        )
        val error = assertFailsWith<ClassroomRemoteException> {
            SchoolClassroomRemoteDataSource(
                transport = transport,
                legacyHttpAvailable = true,
            ).fetchBuildingInfo("思源楼")
        }
        assertEquals(ClassroomRemoteFailure.NETWORK, error.reason)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.startsWith("http://yaya.csoci.com:2333/api/classnum/"))
        assertEquals(0, transport.redirectlessMisses)
    }

    @Test
    fun allowedUrlRequiresHttpHostAndPort() {
        assertTrue("http://yaya.csoci.com:2333/api/classnum/?building=x".isAllowedClassroomUrl())
        assertEquals(false, "https://yaya.csoci.com:2333/api/classnum/".isAllowedClassroomUrl())
        assertEquals(false, "http://yaya.csoci.com/api/classnum/".isAllowedClassroomUrl())
        assertEquals(false, "http://yaya.csoci.com:2333.evil.test/x".isAllowedClassroomUrl())
    }

    @Test
    fun mapsMalformedJsonToParseFailure() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "http://yaya.csoci.com:2333/api/classnum/",
                body = "{}".encodeToByteArray(),
            ),
        )
        val error = assertFailsWith<ClassroomRemoteException> {
            SchoolClassroomRemoteDataSource(
                transport = transport,
                legacyHttpAvailable = true,
            ).fetchBuildingInfo("思源楼")
        }
        assertEquals(ClassroomRemoteFailure.PARSE, error.reason)
    }
}

private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
    private val queue = responses.toMutableList()
    val requests = mutableListOf<SchoolHttpRequest>()
    var redirectlessMisses = 0
    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        redirectlessMisses += 1
        error("classroom capacity must not use auto-follow execute")
    }

    override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse {
        requests += request
        return queue.removeAt(0)
    }

    override fun clearSession() = Unit
}
