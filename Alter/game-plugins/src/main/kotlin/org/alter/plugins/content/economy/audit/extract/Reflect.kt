package org.alter.plugins.content.economy.audit.extract

/**
 * Tiny reflection helpers for reading the private recipe tables inside skill plugins without
 * editing them. Every accessor THROWS on a missing field, so a rename in a skill plugin breaks
 * the auditor loudly (reported as `RECIPE_ADAPTER_BROKEN`) rather than silently dropping a loop.
 */
object Reflect {
    fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw IllegalStateException("no field '$name' on ${obj.javaClass.name}")
    }

    fun str(obj: Any, name: String): String = field(obj, name) as String

    fun int(obj: Any, name: String): Int = (field(obj, name) as Number).toInt()

    fun dbl(obj: Any, name: String): Double = (field(obj, name) as Number).toDouble()

    @Suppress("UNCHECKED_CAST")
    fun list(obj: Any, name: String): List<Any> = (field(obj, name) as Iterable<Any>).toList()

    @Suppress("UNCHECKED_CAST")
    fun <K, V> map(obj: Any, name: String): Map<K, V> = field(obj, name) as Map<K, V>

    fun nullable(obj: Any, name: String): Any? = field(obj, name)
}
