package team.bjtuss.bjtuselfservice.shared.cache

/**
 * Metadata owned by the account's refetchable offline cache.
 *
 * Clearing that cache must preserve local selections, ignored assignments, filters,
 * change history and service configuration. Unclassified keys are retained: storing
 * something in metadata does not make it a disposable cache entry.
 *
 * The namespaces below contain only synchronization timestamps and cached CITEL
 * problem statements. New cache writers must register their key or namespace here.
 */
internal fun isRefetchableAccountMetadata(key: String): Boolean =
    key in refetchableAccountMetadataKeys || refetchableAccountMetadataPrefixes.any(key::startsWith)

private val refetchableAccountMetadataKeys = setOf(
    "profile_name",
    "profile_identity",
    "profile_department",
    "course_current_week",
    "academic_weeks_v1",
    "home_status_v1",
    "courseware_tree_v1",
    "phyvlab_snapshot_v1",
    "physicslab.results",
    "phydemo.results",
    "phydemo.lastSync",
    "citel.tasks",
    "citel.lastSync",
    "agent.mail.inbox",
    "agent.assignment_details.v1",
    "agent.assignment_details.retry_after.v1",
)

private val refetchableAccountMetadataPrefixes = listOf(
    "synced_at_v1.",
    "refresh_synced_at_v1.",
    "citel.problem.",
)
