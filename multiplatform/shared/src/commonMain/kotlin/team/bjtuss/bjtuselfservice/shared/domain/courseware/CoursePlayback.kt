package team.bjtuss.bjtuselfservice.shared.domain.courseware

/** 智慧教学“视频回放”里的一次课（课节）。 */
data class PlaybackLesson(
    val id: Long,
    val courseId: Int,
    /** 老师命名的节次名称；没有命名时为空，界面改用上课时间。 */
    val name: String,
    /** 原样保留学校格式，如 `2026-10-20 16:20-18:10`。 */
    val timeRange: String,
    val classroom: String,
    /** 节次知识点简介；为空表示“暂无知识点”。 */
    val summary: String,
    /** 学校在列表里给出了录像资源 id；没有的课节一定没有视频。 */
    val hasRecording: Boolean,
) {
    val stableKey: String
        get() = "playback:$courseId:$id"

    val title: String
        get() = name.ifBlank { timeRange }
}

/**
 * 一路录像画面。学校录播把同一节课分成多路独立视频：网页播放器把
 * “教师”和“课件（电脑屏幕）”两路并排同步播放（`publicRpType=2,3`）。
 */
enum class PlaybackStreamKind(val label: String, val fileLabel: String, val publicTypeCode: String) {
    TEACHER("教师画面", "教师", "2"),
    SCREEN("屏幕/课件", "课件", "3"),
    STUDENT("学生画面", "学生", "1"),
    TEACHER_CLOSE_UP("教师特写", "教师特写", ""),
    STUDENT_CLOSE_UP("学生特写", "学生特写", ""),
    MOVIE("电影模式", "电影模式", ""),
}

data class PlaybackStream(
    val kind: PlaybackStreamKind,
    /** HTTPS HLS 播放列表地址（lmtN.bjtu.edu.cn:8880）。 */
    val hlsUrl: String,
)

data class PlaybackDetail(
    val lesson: PlaybackLesson,
    /** 学校返回 `haveStream=0` 时为 false：老师没有开放观看。 */
    val permitted: Boolean,
    val streams: List<PlaybackStream>,
    /** 网页默认并排显示的两路；只有一路时就是单画面。 */
    val defaultKinds: List<PlaybackStreamKind>,
    /** 学校给出的录像体积（MB），只用于提示，可能为空。 */
    val sizeMegabytes: String,
) {
    val defaultStreams: List<PlaybackStream>
        get() = defaultKinds.mapNotNull { kind -> streams.firstOrNull { it.kind == kind } }
}

/**
 * 合成输出的清晰度。
 *
 * 学校每路录像只有 1920×1080 一个源（`master.m3u8` 里只列一个变体），没有低清可选，
 * 所以清晰度只能在**合成时**由编码器决定：分辨率和码率一起降，文件体积按比例下降，
 * 合成耗时不变（本来就是重新编码一次）。
 *
 * 每半边宽度都是源的一半：1080p → 540p → 360p → 270p，保持 16:9 不变形。
 */
enum class PlaybackCompositeQuality(
    val label: String,
    val width: Int,
    val height: Int,
    val bitRate: Int,
) {
    /** 每半边 960×540，2.5 Mbps。 */
    HIGH("高", 1920, 540, 2_500_000),

    /** 每半边 640×360，1.2 Mbps。 */
    MEDIUM("中", 1280, 360, 1_200_000),

    /** 每半边 480×270，0.6 Mbps。 */
    LOW("低", 960, 270, 600_000),
    ;

    /** 每小时录像大约占多少空间，给用户一个直观的预期。 */
    val approxMegabytesPerHour: Int
        get() = (bitRate.toLong() * 3_600L / 8L / (1024L * 1024L)).toInt()

    /** 每小时录像大约占多少空间（0.1 GB 为单位的整数，方便自己拼字符串）。 */
    val approxGigabytesPerHourTenths: Int
        get() = approxMegabytesPerHour * 10 / 1024

    /** 码率（0.1 Mbps 为单位的整数，方便自己拼字符串）。 */
    val megabitsPerSecondTenths: Int
        get() = bitRate / 100_000

    /** 设备编码器不支持这个尺寸时退一档；已经最低则没有下一档。 */
    fun smaller(): PlaybackCompositeQuality? = entries.getOrNull(ordinal + 1)
}
