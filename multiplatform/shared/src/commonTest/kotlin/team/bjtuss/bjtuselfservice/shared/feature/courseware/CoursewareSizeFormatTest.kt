package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.test.Test
import kotlin.test.assertEquals

class CoursewareSizeFormatTest {
    @Test
    fun downloadProgressDistinguishesActualAndListedSize() {
        assertEquals("正在下载 2 MB / 10 MB", formatCoursewareDownloadProgress(2L * 1024 * 1024, 10L * 1024 * 1024, "12"))
        assertEquals("正在下载 2 MB / 10.5 MB（课件标注大小）", formatCoursewareDownloadProgress(2L * 1024 * 1024, 0, "10.5"))
        assertEquals("正在下载 2 MB（总大小未知）", formatCoursewareDownloadProgress(2L * 1024 * 1024, 0, null))
    }
    @Test
    fun bareNumberGetsMbUnit() {
        assertEquals("5.34 MB", formatCoursewareSize("5.34"))
        assertEquals("8 MB", formatCoursewareSize("8"))
    }

    @Test
    fun keepsExistingUnit() {
        assertEquals("5.34 MB", formatCoursewareSize("5.34 MB"))
        assertEquals("512 KB", formatCoursewareSize("512 KB"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", formatCoursewareSize(""))
        assertEquals("", formatCoursewareSize("   "))
    }

    @Test
    fun typeLabelsAreHumanReadable() {
        assertEquals("RAR 压缩文件", formatCoursewareTypeLabel("rar"))
        assertEquals("PPT 演示文件", formatCoursewareTypeLabel("ppt"))
        assertEquals("PPTX 演示文件", formatCoursewareTypeLabel("pptx"))
        assertEquals("PDF 文档", formatCoursewareTypeLabel("pdf"))
        assertEquals("DOCX 文档", formatCoursewareTypeLabel("docx"))
        assertEquals("XLSX 表格", formatCoursewareTypeLabel("xlsx"))
        assertEquals("MP4 视频", formatCoursewareTypeLabel("mp4"))
        assertEquals("PNG 图片", formatCoursewareTypeLabel("png"))
        assertEquals("RAR", formatCoursewareTypeBadge("rar"))
        assertEquals("未知类型", formatCoursewareTypeLabel(""))
    }
}
