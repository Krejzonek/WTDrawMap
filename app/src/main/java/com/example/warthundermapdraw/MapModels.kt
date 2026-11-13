package com.example.warthundermapdraw

data class MapData(
    val markers: List<MapMarker>,
    val sectors: List<SpawnSector>,
    val isAirView: Boolean
)

data class MapMarker(
    val x: Float,
    val y: Float,
    val label: String,
    val color: Int,
    val type: MarkerType,
    val iconName: String = "LightTank",
    val directionX: Float = 0f,
    val directionY: Float = -1f
)

data class SpawnPoint(
    val x: Float,
    val y: Float,
    val color: String,
    val type: String
)

data class SpawnSector(
    val points: List<SpawnPoint>,
    val color: String,
    val type: String
)

enum class MarkerType {
    CAPTURE,
    AIRFIELD,
    GROUND_UNIT,
    AIR_UNIT,
    PLAYER_UNIT
}