package tvbox.engine.jar

import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.StringObject
import com.github.unidbg.linux.android.dvm.VM
import com.github.unidbg.linux.android.dvm.api.AssetManager
import com.github.unidbg.linux.android.dvm.array.ArrayObject
import com.github.unidbg.linux.android.dvm.array.ByteArray as GuestBytes
import java.util.IdentityHashMap

internal class GuestObjects(private val vm: VM, private val isArchiveClass: (Class<*>) -> Boolean) {
    private val inputs = IdentityHashMap<Any, DvmObject<*>>()
    private val originals = IdentityHashMap<DvmObject<*>, Any>()
    private val arrays = IdentityHashMap<ArrayObject, Array<Any?>>()
    private var elements = 0

    fun wrap(value: Any?, depth: Int = 0): DvmObject<*>? {
        if (value == null) return null
        inputs[value]?.let { return it }
        val guest = when (value) {
            is String -> StringObject(vm, value)
            is Class<*> -> vm.resolveClass(value.name.replace('.', '/'))
            is android.content.res.AssetManager -> AssetManager(vm, "assets")
            is ByteArray -> GuestBytes(vm, value.copyOf())
            is Array<*> -> {
                reserve(value.size, depth)
                val array = ArrayObject(*arrayOfNulls<DvmObject<*>>(value.size))
                inputs[value] = array
                value.forEachIndexed { index, item -> array.value[index] = wrap(item, depth + 1) }
                array
            }
            else -> vm.resolveClass(value.javaClass.name.replace('.', '/')).newObject(value.takeIf { isArchiveClass(it.javaClass) })
        }
        inputs[value] = guest
        originals[guest] = value
        return guest
    }

    fun unwrap(guest: DvmObject<*>?, expected: String = "Ljava/lang/Object;", depth: Int = 0): Any? {
        if (guest == null) return null
        val value = originals[guest] ?: when (guest) {
            is StringObject -> guest.value
            is GuestBytes -> guest.value.copyOf()
            is ArrayObject -> arrays[guest] ?: run {
                reserve(guest.length(), depth)
                val result: Array<Any?> = if (expected == "[Ljava/lang/String;") {
                    @Suppress("UNCHECKED_CAST")
                    (arrayOfNulls<String>(guest.length()) as Array<Any?>)
                } else arrayOfNulls(guest.length())
                arrays[guest] = result
                val component = if (expected == "[Ljava/lang/String;") "Ljava/lang/String;" else "Ljava/lang/Object;"
                guest.value.forEachIndexed { index, item -> result[index] = unwrap(item, component, depth + 1) }
                result
            }
            else -> guest.value?.takeIf { isArchiveClass(it.javaClass) } ?: error("Android JNI returned an unmapped guest reference")
        }
        check(when (expected) {
            "[B" -> value is ByteArray
            "Ljava/lang/String;" -> value is String
            "Ljava/lang/Class;" -> value is Class<*>
            "[Ljava/lang/Object;" -> value is Array<*>
            "[Ljava/lang/String;" -> value is Array<*> && value.javaClass.componentType == String::class.java
            else -> true
        }) { "Android JNI returned an unexpected reference type" }
        return value
    }

    fun copyBack() {
        originals.forEach { (guest, original) ->
            when {
                guest is GuestBytes && original is ByteArray -> guest.value.copyInto(original)
                guest is ArrayObject && original is Array<*> -> {
                    val replacement = guest.value.map { unwrap(it) }
                    val component = original.javaClass.componentType
                    check(replacement.all { it == null || component.isInstance(it) }) { "Android JNI returned an unexpected array element" }
                    @Suppress("UNCHECKED_CAST")
                    (original as Array<Any?>).let { destination -> replacement.forEachIndexed { index, item -> destination[index] = item } }
                }
            }
        }
    }

    private fun reserve(size: Int, depth: Int) {
        check(depth < 16 && size <= 16_384 - elements) { "Android JNI object array limit reached" }
        elements += size
    }
}
