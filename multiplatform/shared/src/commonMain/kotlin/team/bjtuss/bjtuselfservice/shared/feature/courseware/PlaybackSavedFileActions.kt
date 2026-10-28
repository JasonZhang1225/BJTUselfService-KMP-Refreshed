package team.bjtuss.bjtuselfservice.shared.feature.courseware

/** 打开已经保存的文件，不读取内容、不复制视频，也不依赖下载任务仍持有保存句柄。 */
internal interface PlaybackSavedFileActions {
    /** 返回适合就地显示的错误；null 表示已交给系统。 */
    suspend fun open(path: String, folder: Boolean): String?
}

internal expect fun playbackSavedFileActions(): PlaybackSavedFileActions?
