package team.bjtuss.bjtuselfservice.shared.data.courseware

import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

/** 进程在后台照常运行（Android 由前台服务保活），直接逐个请求分片即可。 */
actual fun platformBackgroundSegmentSource(transport: SchoolHttpTransport): HlsSegmentSource? = null
