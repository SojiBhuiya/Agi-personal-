package com.agi.assistant.core.update

/**
 * Minimal semantic version: MAJOR.MINOR.PATCH with optional pre-release
 * (`1.0.0-beta.1`) and ignored build metadata (`+42`). A leading `v`/`V`
 * (GitHub tag convention) and surrounding whitespace are tolerated.
 *
 * Ordering follows SemVer 2.0: numeric parts first, then a pre-release
 * version sorts *before* the same release version (`1.0.0-rc1 < 1.0.0`).
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // No pre-release > any pre-release.
        if (preRelease == null && other.preRelease == null) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val CORE = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        /** Parses `0.2.0`, `v0.2.0`, `1.0`, `2`, `1.0.0-beta.1+build`; returns null when unparseable. */
        fun parse(raw: String?): SemanticVersion? {
            if (raw == null) return null
            var s = raw.trim()
            if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
            // Tolerate names such as "release-0.2.0" or "agi-assistant-0.2.0" by taking the last version-looking token.
            val m = CORE.find(s) ?: Regex("(\\d+\\.\\d+(?:\\.\\d+)?(?:-[0-9A-Za-z.-]+)?)").findAll(s).lastOrNull()?.let { CORE.find(it.value) } ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
            val patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = m.groupValues[4].ifEmpty { null }
            return SemanticVersion(major, minor, patch, pre)
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val pa = a.split('.'); val pb = b.split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: return -1 // shorter pre-release sorts first
                val y = pb.getOrNull(i) ?: return 1
                val nx = x.toIntOrNull(); val ny = y.toIntOrNull()
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1 // numeric < alphanumeric
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
