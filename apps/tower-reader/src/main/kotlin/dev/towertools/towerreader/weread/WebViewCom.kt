package dev.towertools.towerreader.weread

import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.util.concurrent.atomic.AtomicInteger

/** Small, explicit ABI binding to the interfaces in the pinned Microsoft WebView2 SDK. */
internal class Com(val pointer: Pointer) {
    fun call(slot: Int, vararg args: Any?): Int = Function.getFunction(
        pointer.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()), Function.ALT_CONVENTION
    ).invokeInt(arrayOf(pointer, *args)).also { checkHr(it) }
    fun number(slot: Int): Int = IntByReference().also { call(slot, it) }.value
    fun objectAt(slot: Int): Com = PointerByReference().also { call(slot, it) }.value.let(::Com)
    fun text(slot: Int): String {
        val out = PointerByReference(); call(slot, out)
        val value = out.value ?: return ""
        return try { value.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(value) }
    }
    fun query(iid: String): Com = PointerByReference().also { call(0, GUID(iid), it) }.value.let(::Com)
    fun retain(): Com { call(1); return this }
    fun release() { call(2) }
}

internal fun checkHr(result: Int) {
    check(result >= 0) { "WebView2 调用失败 (0x${Integer.toHexString(result)})" }
}

internal fun interface ComEvent : StdCallCallback { fun invoke(self: Pointer, sender: Pointer, args: Pointer?): Int }
internal fun interface ComCompletion : StdCallCallback { fun invoke(self: Pointer, result: Int, value: Pointer?): Int }
internal fun interface ComSuspend : StdCallCallback { fun invoke(self: Pointer, result: Int, suspended: Int): Int }
internal fun interface ComFinished : StdCallCallback { fun invoke(self: Pointer, result: Int): Int }
internal fun interface ComQuery : StdCallCallback { fun invoke(self: Pointer, iid: Pointer, result: PointerByReference): Int }
internal fun interface ComReference : StdCallCallback { fun invoke(self: Pointer): Int }

/** Keep this object alive until the native references have gone, including async completions. */
internal class ComCallback(iid: String, private val callback: Callback) {
    private val references = AtomicInteger(1)
    private val accepted = listOf(GUID(iid).toByteArray(), GUID("00000000-0000-0000-C000-000000000046").toByteArray())
    private val query = ComQuery { _, requested, out ->
        if (accepted.any { it.contentEquals(requested.getByteArray(0, 16)) }) {
            out.value = pointer; references.incrementAndGet(); 0
        } else { out.value = null; 0x80004002.toInt() }
    }
    private val addRef = ComReference { references.incrementAndGet() }
    private val release = ComReference { references.decrementAndGet() }
    private val vtable = Memory(4L * Native.POINTER_SIZE).also { table ->
        listOf(query, addRef, release, callback).forEachIndexed { index, method ->
            table.setPointer(index * Native.POINTER_SIZE.toLong(), CallbackReference.getFunctionPointer(method))
        }
    }
    val pointer: Memory = Memory(Native.POINTER_SIZE.toLong()).also { it.setPointer(0, vtable) }
}

@Structure.FieldOrder("left", "top", "right", "bottom")
internal class WebRect : Structure(), Structure.ByValue {
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
}
