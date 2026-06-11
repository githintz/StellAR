package com.stellar.app.data

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bundled star catalogue (Hipparcos subset via the d3-celestial dataset),
 * loaded from assets/stars.csv. Stored as flat arrays for cheap upload
 * to the GPU.
 */
class StarCatalog(
    val size: Int,
    val hip: IntArray,
    val raDeg: DoubleArray,
    val decDeg: DoubleArray,
    val mag: FloatArray,
    val bv: FloatArray,
    /** Proper name ("Sirius") or empty. */
    val name: Array<String>,
    /** Bayer designation ("Alpha CMa") or empty. */
    val designation: Array<String>
) {
    companion object {
        fun load(assets: AssetManager): StarCatalog {
            val rows = ArrayList<Array<String>>(6000)
            BufferedReader(InputStreamReader(assets.open("stars.csv"))).use { reader ->
                reader.readLine() // header
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        // hip,ra,dec,mag,bv,name,designation
                        rows.add(line.split(",").toTypedArray())
                    }
                    line = reader.readLine()
                }
            }

            val n = rows.size
            val hip = IntArray(n)
            val ra = DoubleArray(n)
            val dec = DoubleArray(n)
            val mag = FloatArray(n)
            val bv = FloatArray(n)
            val name = Array(n) { "" }
            val desig = Array(n) { "" }

            for (i in 0 until n) {
                val r = rows[i]
                hip[i] = r[0].toInt()
                ra[i] = r[1].toDouble()
                dec[i] = r[2].toDouble()
                mag[i] = r[3].toFloat()
                bv[i] = r[4].toFloatOrNull() ?: 0f
                if (r.size > 5) name[i] = r[5]
                if (r.size > 6) desig[i] = r[6]
            }
            return StarCatalog(n, hip, ra, dec, mag, bv, name, desig)
        }
    }
}
