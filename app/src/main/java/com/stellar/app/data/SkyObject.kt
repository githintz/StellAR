package com.stellar.app.data

/**
 * A selectable / searchable celestial object. For solar-system bodies the
 * RA/Dec stored here is a placeholder; their live position is resolved
 * from the latest ephemeris snapshot at draw time.
 */
data class SkyObject(
    val name: String,
    val type: Type,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Float,
    val designation: String = ""
) {
    enum class Type { STAR, PLANET, MOON, SUN, CONSTELLATION }

    val isSolarSystem: Boolean
        get() = type == Type.PLANET || type == Type.MOON || type == Type.SUN

    fun displayLabel(): String =
        if (designation.isNotEmpty()) "$name ($designation)" else name
}
