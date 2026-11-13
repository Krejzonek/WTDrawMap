package com.example.warthundermapdraw

import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

object WarThunderMapParser {

    private const val TAG = "WarThunderMapParser"
    private const val SPAWN_CLUSTER_DISTANCE = 0.035f

    private const val TEAM_BLUE = "#1952FF"
    private const val SQUAD_GREEN = "#AAFF8E"

    private val FRIENDLY_COLORS = setOf(
        TEAM_BLUE,
        SQUAD_GREEN
    )

    fun parse(objects: JSONObject): MapData {
        val markers = mutableListOf<MapMarker>()
        val spawnPoints = mutableListOf<SpawnPoint>()

        val objectList = getObjectList(objects)
        val airView = isAirView(objectList)

        for (obj in objectList) {
            parseObject(
                obj,
                markers,
                spawnPoints,
                airView
            )
        }

        val captureIndices = markers.indices
            .filter {
                markers[it].type == MarkerType.CAPTURE
            }
            .sortedWith(
                compareBy<Int> { markers[it].x }
                    .thenBy { markers[it].y }
            )

        captureIndices.forEachIndexed { index, markerIndex ->
            val marker = markers[markerIndex]

            if (marker.label.isBlank()) {
                markers[markerIndex] = marker.copy(
                    label = ('A'.code + index).toChar().toString()
                )
            }
        }

        Log.d(
            TAG,
            "Parsed markers=${markers.size}, spawns=${spawnPoints.size}, airView=$airView"
        )

        return MapData(
            markers = markers,
            sectors = clusterSpawnPoints(spawnPoints),
            isAirView = airView
        )
    }

    private fun getObjectList(
        objects: JSONObject
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()

        val array = objects.optJSONArray("items")
            ?: objects.optJSONArray("objects")

        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(obj)
            }
        } else {
            val keys = objects.keys()

            while (keys.hasNext()) {
                val obj = objects.optJSONObject(keys.next()) ?: continue
                result.add(obj)
            }
        }

        return result
    }

    private fun normalizeUnitValue(value: String): String {
        return value.lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
    }

    private fun isAirObject(
        type: String,
        icon: String,
        obj: JSONObject
    ): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return combined.contains("aircraft") ||
                combined.contains("fighter") ||
                combined.contains("bomber") ||
                combined.contains("helicopter") ||
                combined.contains("attacker") ||
                combined.contains("interceptor") ||
                combined.contains("assault")
    }

    private fun isShipObject(
        type: String,
        icon: String,
        obj: JSONObject
    ): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return combined.contains("ship") ||
                combined.contains("destroyer") ||
                combined.contains("cruiser") ||
                combined.contains("battleship") ||
                combined.contains("torpedoboat") ||
                combined.contains("gunboat") ||
                combined.contains("frigate")
    }

    private fun isGroundObject(
        type: String,
        icon: String,
        obj: JSONObject
    ): Boolean {
        val combined = normalizeUnitValue(
            "$type $icon ${obj.optString("unit_class", "")} ${obj.optString("class", "")}"
        )

        return type == "ground_model" ||
                type == "vehicle" ||
                type.contains("ground") ||
                combined.contains("tank") ||
                combined.contains("spaa") ||
                combined.contains("ifv")
    }

    private fun isAirView(
        objects: List<JSONObject>
    ): Boolean {
        return objects.any { obj ->
            val type = obj.optString("type", "").lowercase()
            val icon = obj.optString("icon", "").lowercase()

            isAirObject(type, icon, obj) &&
                    (icon == "player" || obj.optBoolean("is_player", false))
        }
    }

    private fun parseObject(
        obj: JSONObject,
        markers: MutableList<MapMarker>,
        spawnPoints: MutableList<SpawnPoint>,
        airView: Boolean
    ) {
        val x = getFloat(obj, "x") ?: return
        val y = getFloat(obj, "y") ?: return

        if (x !in 0f..1f || y !in 0f..1f) return

        val type = obj.optString("type", "").lowercase()
        val icon = obj.optString("icon", "").lowercase()
        val color = getMapObjectColor(obj)

        val isSpawn =
            type.contains("respawn") ||
                    type.startsWith("respawn_base") ||
                    icon.contains("respawn") ||
                    obj.has("respawn_base_tank") ||
                    obj.has("respawn_base_fighter") ||
                    obj.has("respawn_base_bomber")

        if (isSpawn) {
            spawnPoints.add(
                SpawnPoint(
                    x,
                    y,
                    color,
                    getSpawnType(type, icon, obj)
                )
            )
            return
        }

        val isAirfield =
            type.contains("airfield") ||
                    icon.contains("airfield") ||
                    obj.has("airfield")

        if (isAirfield) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = parseColorOrDefault(color, Color.WHITE),
                    type = MarkerType.AIRFIELD,
                    iconName = "airfield"
                )
            )
            return
        }

        val isCapture =
            type.contains("capture") ||
                    type.contains("zone") ||
                    icon.contains("capture") ||
                    icon.contains("zone") ||
                    obj.has("capture_zone")

        if (isCapture) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = getCaptureLabel(obj),
                    color = parseColorOrDefault(
                        color,
                        Color.rgb(180, 180, 180)
                    ),
                    type = MarkerType.CAPTURE,
                    iconName = "capture_zone"
                )
            )
            return
        }

        val isPlayer =
            icon == "player" ||
                    type == "player" ||
                    obj.optBoolean("is_player", false)

        val isAircraft = isAirObject(type, icon, obj)
        val isShip = isShipObject(type, icon, obj)
        val isGroundVehicle = isGroundObject(type, icon, obj)

        if (!isPlayer && !isAircraft && !isShip && !isGroundVehicle) {
            return
        }

        val unitIcon =
            if (isPlayer) "Player" else getUnitIcon(type, icon, obj)

        val directionX = getFloat(obj, "dx") ?: 0f
        val directionY = getFloat(obj, "dy") ?: -1f

        if (isPlayer) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = Color.WHITE,
                    type = MarkerType.PLAYER_UNIT,
                    iconName = "Player",
                    directionX = directionX,
                    directionY = directionY
                )
            )
            return
        }

        if (!isFriendlyColor(color)) return

        val teamColor = getFriendlyTeamColor(color)

        if (airView && isAircraft) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = teamColor,
                    type = MarkerType.AIR_UNIT,
                    iconName = unitIcon,
                    directionX = directionX,
                    directionY = directionY
                )
            )
        } else if (!airView && (isGroundVehicle || isShip)) {
            markers.add(
                MapMarker(
                    x = x,
                    y = y,
                    label = "",
                    color = teamColor,
                    type = MarkerType.GROUND_UNIT,
                    iconName = unitIcon,
                    directionX = directionX,
                    directionY = directionY
                )
            )
        }
    }

    private fun getUnitIcon(
        type: String,
        icon: String,
        obj: JSONObject
    ): String {
        val normalizedIcon = normalizeUnitValue(icon)

        val combined = normalizeUnitValue(
            "$type $icon " +
                    obj.optString("name", "") + " " +
                    obj.optString("class", "") + " " +
                    obj.optString("unit_class", "") + " " +
                    obj.optString("vehicle", "") + " " +
                    obj.optString("unit", "") + " " +
                    obj.optString("model", "")
        )

        return when {
            normalizedIcon.contains("heavytank") ||
                    combined.contains("heavytank") -> "HeavyTank"

            normalizedIcon.contains("lighttank") ||
                    combined.contains("lighttank") -> "LightTank"

            normalizedIcon.contains("mediumtank") ||
                    combined.contains("mediumtank") -> "MediumTank"

            normalizedIcon.contains("tankdestroyer") ||
                    combined.contains("tankdestroyer") ||
                    combined.contains("tankhunter") ||
                    combined.contains("casemate") -> "TankDestroyer"

            normalizedIcon.contains("spaa") ||
                    combined.contains("spaa") ||
                    combined.contains("antiaircraft") -> "SPAA"

            normalizedIcon.contains("torpedobomber") ||
                    combined.contains("torpedobomber") -> "Bomber"

            normalizedIcon.contains("heavybomber") ||
                    combined.contains("heavybomber") ||
                    combined.contains("strategicbomber") ||
                    normalizedIcon.contains("bomber") ||
                    combined.contains("bomber") -> "Bomber"

            normalizedIcon.contains("attacker") ||
                    normalizedIcon.contains("assault") ||
                    combined.contains("attacker") ||
                    combined.contains("groundattack") ||
                    combined.contains("assault") -> "Assault"

            normalizedIcon.contains("fighter") ||
                    combined.contains("fighter") ||
                    combined.contains("interceptor") -> "Fighter"

            normalizedIcon.contains("helicopter") ||
                    combined.contains("helicopter") -> "Helicopter"

            normalizedIcon.contains("battleship") ||
                    normalizedIcon.contains("battlecruiser") ||
                    combined.contains("battleship") ||
                    combined.contains("battlecruiser") -> "Battleship"

            normalizedIcon.contains("heavycruiser") ||
                    combined.contains("heavycruiser") -> "HeavyCruiser"

            normalizedIcon.contains("lightcruiser") ||
                    normalizedIcon.contains("cruiser") ||
                    combined.contains("lightcruiser") ||
                    combined.contains("cruiser") -> "Cruiser"

            normalizedIcon.contains("frigate") ||
                    combined.contains("frigate") -> "Frigate"

            normalizedIcon.contains("destroyer") ||
                    combined.contains("destroyer") -> "Destroyer"

            normalizedIcon.contains("torpedoboat") ||
                    normalizedIcon.contains("torpedocraft") ||
                    combined.contains("torpedoboat") ||
                    combined.contains("torpedocraft") -> "TorpedoBoat"

            normalizedIcon.contains("gunboat") ||
                    normalizedIcon.contains("patrol") ||
                    normalizedIcon.contains("motorboat") ||
                    combined.contains("gunboat") ||
                    combined.contains("patrol") ||
                    combined.contains("motorboat") -> "Gunboat"

            isShipObject(type, icon, obj) -> "Ship"
            isAirObject(type, icon, obj) -> "Fighter"
            else -> "LightTank"
        }
    }

    private fun getFriendlyTeamColor(color: String): Int {
        val normalized = normalizeColor(color)

        return when (normalized) {
            TEAM_BLUE -> Color.rgb(25, 82, 255)
            SQUAD_GREEN -> Color.rgb(170, 255, 142)

            else -> {
                try {
                    val parsed = Color.parseColor(normalized)

                    val red = Color.red(parsed)
                    val green = Color.green(parsed)
                    val blue = Color.blue(parsed)

                    if (green > red * 1.3f && green > blue * 0.8f) {
                        Color.rgb(170, 255, 142)
                    } else {
                        Color.rgb(25, 82, 255)
                    }
                } catch (_: Exception) {
                    Color.rgb(25, 82, 255)
                }
            }
        }
    }

    private fun getCaptureLabel(obj: JSONObject): String {
        val possibleLabels = listOf(
            obj.optString("name", ""),
            obj.optString("label", ""),
            obj.optString("id", ""),
            obj.optString("icon", "")
        )

        for (value in possibleLabels) {
            val label = value.trim().uppercase()
            if (label.matches(Regex("[A-Z]"))) return label
        }

        return ""
    }

    private fun getSpawnType(
        type: String,
        icon: String,
        obj: JSONObject
    ): String {
        val combined = normalizeUnitValue("$type $icon $obj")

        return when {
            combined.contains("fighter") -> "FIGHTER"
            combined.contains("bomber") -> "BOMBER"
            combined.contains("tank") -> "TANK"
            else -> "SPAWN"
        }
    }

    private fun getFloat(
        obj: JSONObject,
        vararg keys: String
    ): Float? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue

            val value = obj.opt(key)

            val number = when (value) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull()
                else -> null
            }

            if (number != null && number.isFinite()) return number
        }

        return null
    }

    private fun getMapObjectColor(obj: JSONObject): String {
        val value = obj.opt("color")
            ?: obj.opt("colour")
            ?: obj.opt("color[]")
            ?: return ""

        return when (value) {
            is String -> normalizeColor(value)

            is JSONArray -> {
                if (value.length() >= 3) {
                    val r = value.optInt(0).coerceIn(0, 255)
                    val g = value.optInt(1).coerceIn(0, 255)
                    val b = value.optInt(2).coerceIn(0, 255)

                    String.format("#%02X%02X%02X", r, g, b)
                } else {
                    ""
                }
            }

            else -> ""
        }
    }

    private fun normalizeColor(color: String): String {
        var result = color.trim().uppercase()

        if (!result.startsWith("#")) {
            result = "#$result"
        }

        return result
    }

    private fun parseColorOrDefault(
        color: String,
        defaultColor: Int
    ): Int {
        if (color.isBlank()) return defaultColor

        return try {
            Color.parseColor(normalizeColor(color))
        } catch (_: Exception) {
            defaultColor
        }
    }

    private fun isFriendlyColor(color: String): Boolean {
        if (color.isBlank()) return false

        val normalized = normalizeColor(color)

        if (normalized in FRIENDLY_COLORS) return true

        return try {
            val parsed = Color.parseColor(normalized)

            val red = Color.red(parsed)
            val green = Color.green(parsed)
            val blue = Color.blue(parsed)

            (blue > red * 1.25f && blue > green * 0.8f) ||
                    (green > red * 1.3f && green > blue * 0.8f)
        } catch (_: Exception) {
            false
        }
    }

    private fun clusterSpawnPoints(
        points: List<SpawnPoint>
    ): List<SpawnSector> {
        if (points.isEmpty()) return emptyList()

        val remaining = points.toMutableList()
        val sectors = mutableListOf<SpawnSector>()

        while (remaining.isNotEmpty()) {
            val cluster = mutableListOf<SpawnPoint>()
            val queue = ArrayDeque<SpawnPoint>()

            queue.add(remaining.removeAt(0))

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cluster.add(current)

                val nearby = remaining.filter {
                    distance(
                        current.x,
                        current.y,
                        it.x,
                        it.y
                    ) <= SPAWN_CLUSTER_DISTANCE
                }

                for (point in nearby) {
                    remaining.remove(point)
                    queue.add(point)
                }
            }

            sectors.add(
                SpawnSector(
                    points = cluster,
                    color = cluster.first().color,
                    type = cluster.first().type
                )
            )
        }

        return sectors
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        return hypot(x2 - x1, y2 - y1)
    }
}