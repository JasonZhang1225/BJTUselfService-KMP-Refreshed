package team.bjtuss.bjtuselfservice.shared.feature.shell

import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource

/** UI commands only. Refresh policy and coroutine ownership belong to the account. */
internal class ShellRefresher(private val session: AuthenticatedSession) {
    fun refresh(section: AppSection, route: AppRoute) { session.refreshService?.refresh(section, route) }
    fun refreshAssignmentSource(source: AssignmentSource) { session.refreshService?.requestAssignmentSource(source) }
    fun retryPhyVlabDetail() { session.refreshService?.retryPhyVlabDetail() }
}
