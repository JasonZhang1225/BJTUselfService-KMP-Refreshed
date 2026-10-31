package team.bjtuss.bjtuselfservice.windows

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.UUID

/** Windows Vista 及以后版本的系统文件面板。调用线程必须在面板关闭前保持存活。 */
internal object WindowsFileDialog {
    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, result: PointerByReference): Int
        fun CoTaskMemFree(memory: Pointer)
    }

    private val ole by lazy { Native.load("ole32", Ole32::class.java) }
    private const val CANCELLED = -2147023673 // HRESULT_FROM_WIN32(ERROR_CANCELLED)

    fun save(suggestedName: String, owner: Pointer? = null): File? = show(false, suggestedName, owner)
    fun folder(owner: Pointer? = null): File? = show(true, null, owner)

    private fun show(folder: Boolean, suggestedName: String?, owner: Pointer?): File? {
        // IO 调度线程可能之前使用过其他 COM 模型，因此在独立 STA 线程中运行。
        var value: File? = null
        var failure: Throwable? = null
        val thread = Thread {
            val initialized = ole.CoInitializeEx(null, 2)
            try {
                check(initialized >= 0) { "COM initialization failed: $initialized" }
                val clsid = guid(if (folder) "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7" else "C0B4E2F3-BA21-4773-8DBA-335EC946EB8B")
                val iid = guid(if (folder) "D57C7288-D4AD-4768-BE02-9D969532D960" else "84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB")
                val out = PointerByReference()
                check(ole.CoCreateInstance(clsid, null, 1, iid, out) >= 0) { "Cannot create file dialog" }
                val dialog = out.value
                try {
                    val options = IntByReference()
                    check(call(dialog, 10, options) >= 0)
                    // Filesystem paths only; directory picker selects folders, save panel prompts before overwrite.
                    check(call(dialog, 9, options.value or 0x40 or if (folder) 0x20 else 0x2) >= 0)
                    if (suggestedName != null) check(call(dialog, 15, WString(suggestedName)) >= 0)
                    val shown = call(dialog, 3, owner)
                    if (shown != CANCELLED) {
                        check(shown >= 0) { "File dialog failed: $shown" }
                        val itemOut = PointerByReference()
                        check(call(dialog, 20, itemOut) >= 0)
                        val item = itemOut.value
                        try {
                            val pathOut = PointerByReference()
                            check(call(item, 5, 0x80058000.toInt(), pathOut) >= 0)
                            val path = pathOut.value
                            try { value = File(path.getWideString(0)) } finally { ole.CoTaskMemFree(path) }
                        } finally { call(item, 2) }
                    }
                } finally { call(dialog, 2) }
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (initialized >= 0) ole.CoUninitialize()
            }
        }
        thread.name = "WindowsFileDialog"
        thread.start()
        thread.join()
        failure?.let { throw it }
        return value
    }

    private fun call(instance: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = instance.getPointer(0)
        val function = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return function.invokeInt(arrayOf(instance, *args))
    }

    private fun guid(value: String): Memory {
        val uuid = UUID.fromString(value)
        val memory = Memory(16)
        val msb = uuid.mostSignificantBits
        memory.setInt(0, (msb ushr 32).toInt())
        memory.setShort(4, (msb ushr 16).toShort())
        memory.setShort(6, msb.toShort())
        val lsb = uuid.leastSignificantBits
        for (i in 0..7) memory.setByte(8L + i, (lsb ushr (56 - i * 8)).toByte())
        return memory
    }
}
