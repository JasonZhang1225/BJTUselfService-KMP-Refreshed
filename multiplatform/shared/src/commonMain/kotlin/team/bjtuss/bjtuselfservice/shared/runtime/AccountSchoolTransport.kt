package team.bjtuss.bjtuselfservice.shared.runtime

import team.bjtuss.bjtuselfservice.shared.network.*

/** Borrowed transport: ending an account revokes access without closing the runtime's shared clients. */
internal class AccountSchoolTransport(
    private val delegate: SchoolHttpTransport,
    private val work: AccountWork,
) : SchoolHttpTransport {
    override suspend fun execute(request: SchoolHttpRequest) = work.run { delegate.execute(request) }
    override suspend fun executePublic(request: SchoolHttpRequest) = work.run { delegate.executePublic(request) }
    override suspend fun executeWithoutRedirects(request: SchoolHttpRequest) = work.run { delegate.executeWithoutRedirects(request) }
    override suspend fun sessionCookiesFor(url: String) = work.run { delegate.sessionCookiesFor(url) }

    override suspend fun adoptPhyVlabSession(
        session: PhyVlabLoginSession,
        verify: suspend (SchoolHttpResponse) -> Boolean,
    ) = work.run {
        delegate.adoptPhyVlabSession(session) { response ->
            verify(response).also { work.checkActive() }
        }
    }

    override suspend fun clearPhyVlabSession() = work.run { delegate.clearPhyVlabSession() }
    override suspend fun stream(request: SchoolHttpRequest, onHeaders: suspend (SchoolHttpResponse) -> Unit, onChunk: suspend (ByteArray) -> Unit) =
        work.run { delegate.stream(request, { work.checkActive(); onHeaders(it) }, { work.checkActive(); onChunk(it) }) }
    override fun clearSession() = work.mutate { delegate.clearSession() }
    override fun clearPhysicsDemoSession() = work.mutate { delegate.clearPhysicsDemoSession() }
}
