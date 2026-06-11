package com.stellar.app.rendering

/**
 * Approximate B-V colour index -> linear RGB. A coarse piecewise ramp is
 * plenty: on a phone screen star colour is a subtle hint, not photometry.
 */
object StarColor {

    // (bv, r, g, b) control points, bv ascending.
    private val RAMP = floatArrayOf(
        -0.4f, 0.62f, 0.75f, 1.00f,  // hot blue (Rigel-like)
        0.0f, 0.80f, 0.87f, 1.00f,  // blue-white (Vega)
        0.3f, 1.00f, 1.00f, 1.00f,  // white
        0.6f, 1.00f, 0.96f, 0.84f,  // yellow-white (Sun)
        1.0f, 1.00f, 0.86f, 0.64f,  // orange (Arcturus)
        1.6f, 1.00f, 0.72f, 0.46f,  // red-orange (Betelgeuse)
        2.5f, 1.00f, 0.58f, 0.34f   // deep red (carbon stars)
    )

    /** Writes r,g,b into [out] at [offset]. */
    fun fromBv(bv: Float, out: FloatArray, offset: Int) {
        val n = RAMP.size / 4
        if (bv <= RAMP[0]) {
            out[offset] = RAMP[1]; out[offset + 1] = RAMP[2]; out[offset + 2] = RAMP[3]
            return
        }
        for (i in 0 until n - 1) {
            val b0 = RAMP[i * 4]
            val b1 = RAMP[(i + 1) * 4]
            if (bv <= b1) {
                val t = (bv - b0) / (b1 - b0)
                for (c in 0..2) {
                    val v0 = RAMP[i * 4 + 1 + c]
                    val v1 = RAMP[(i + 1) * 4 + 1 + c]
                    out[offset + c] = v0 + (v1 - v0) * t
                }
                return
            }
        }
        val last = (n - 1) * 4
        out[offset] = RAMP[last + 1]
        out[offset + 1] = RAMP[last + 2]
        out[offset + 2] = RAMP[last + 3]
    }
}
