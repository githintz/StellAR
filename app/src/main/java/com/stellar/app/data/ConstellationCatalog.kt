package com.stellar.app.data

import android.content.res.AssetManager
import org.json.JSONArray

/** One constellation: display name, label anchor and line segments. */
class Constellation(
    val id: String,
    val name: String,
    val labelRaDeg: Double,
    val labelDecDeg: Double,
    /** Polylines; each is a list of (raDeg, decDeg) pairs. */
    val lines: List<DoubleArray>
)

object ConstellationCatalog {

    fun load(assets: AssetManager): List<Constellation> {
        val text = assets.open("constellations.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val result = ArrayList<Constellation>(arr.length())

        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val label = c.getJSONArray("label")
            val linesJson = c.getJSONArray("lines")
            val lines = ArrayList<DoubleArray>(linesJson.length())

            for (j in 0 until linesJson.length()) {
                val seg = linesJson.getJSONArray(j)
                // Flattened [ra0, dec0, ra1, dec1, ...]
                val flat = DoubleArray(seg.length() * 2)
                for (k in 0 until seg.length()) {
                    val pt = seg.getJSONArray(k)
                    flat[k * 2] = pt.getDouble(0)
                    flat[k * 2 + 1] = pt.getDouble(1)
                }
                lines.add(flat)
            }

            result.add(
                Constellation(
                    id = c.getString("id"),
                    name = c.getString("name"),
                    labelRaDeg = label.getDouble(0),
                    labelDecDeg = label.getDouble(1),
                    lines = lines
                )
            )
        }
        return result
    }
}
