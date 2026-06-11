package com.stellar.app.data

import android.content.Context

/** Everything loaded from bundled assets, ready for rendering and search. */
class SkyData(
    val stars: StarCatalog,
    val constellations: List<Constellation>,
    /** Searchable objects: planets, named stars, constellations. */
    val searchIndex: List<SkyObject>
)

object SkyRepository {

    private val SOLAR_SYSTEM = listOf(
        SkyObject("Sun", SkyObject.Type.SUN, 0.0, 0.0, -26.7f),
        SkyObject("Moon", SkyObject.Type.MOON, 0.0, 0.0, -12.7f),
        SkyObject("Mercury", SkyObject.Type.PLANET, 0.0, 0.0, 0.2f),
        SkyObject("Venus", SkyObject.Type.PLANET, 0.0, 0.0, -4.1f),
        SkyObject("Mars", SkyObject.Type.PLANET, 0.0, 0.0, 0.7f),
        SkyObject("Jupiter", SkyObject.Type.PLANET, 0.0, 0.0, -2.2f),
        SkyObject("Saturn", SkyObject.Type.PLANET, 0.0, 0.0, 0.5f),
        SkyObject("Uranus", SkyObject.Type.PLANET, 0.0, 0.0, 5.7f),
        SkyObject("Neptune", SkyObject.Type.PLANET, 0.0, 0.0, 7.9f)
    )

    /** Blocking; call from a background dispatcher. */
    fun load(context: Context): SkyData {
        val assets = context.assets
        val stars = StarCatalog.load(assets)
        val constellations = ConstellationCatalog.load(assets)

        val index = ArrayList<SkyObject>(600)
        index.addAll(SOLAR_SYSTEM)
        for (i in 0 until stars.size) {
            if (stars.name[i].isNotEmpty()) {
                index.add(
                    SkyObject(
                        name = stars.name[i],
                        type = SkyObject.Type.STAR,
                        raDeg = stars.raDeg[i],
                        decDeg = stars.decDeg[i],
                        mag = stars.mag[i],
                        designation = stars.designation[i]
                    )
                )
            }
        }
        for (c in constellations) {
            index.add(
                SkyObject(
                    name = c.name,
                    type = SkyObject.Type.CONSTELLATION,
                    raDeg = c.labelRaDeg,
                    decDeg = c.labelDecDeg,
                    mag = 0f
                )
            )
        }
        return SkyData(stars, constellations, index)
    }
}
