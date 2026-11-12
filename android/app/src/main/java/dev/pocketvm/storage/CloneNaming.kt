package dev.pocketvm.storage

/** 复制虚拟机时的命名规则（纯函数，便于单测）。 */
object CloneNaming {

    private const val SUFFIX = " 副本"

    /**
     * `Windows 11` → `Windows 11 副本`；重名时依次 `… 副本 2`、`… 副本 3`。
     * 只比较完全相同的名字，不做大小写折叠（列表展示按原名）。
     */
    fun uniqueCloneName(baseName: String, existingNames: Collection<String>): String {
        val base = baseName.trim().ifBlank { "虚拟机" }
        val first = "$base$SUFFIX"
        if (first !in existingNames) return first
        var n = 2
        while ("$first $n" in existingNames) n++
        return "$first $n"
    }
}
