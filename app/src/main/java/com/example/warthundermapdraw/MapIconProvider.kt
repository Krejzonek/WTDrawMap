package com.example.warthundermapdraw

object MapIconProvider {

    fun getIconRes(iconName: String): Int {
        return when (iconName) {
            "LightTank" -> R.drawable.ic_light_tank
            "MediumTank" -> R.drawable.ic_medium_tank
            "HeavyTank" -> R.drawable.ic_heavy_tank
            "TankDestroyer" -> R.drawable.ic_tank_destroyer
            "SPAA" -> R.drawable.ic_spaa

            "Fighter" -> R.drawable.ic_fighter
            "Assault" -> R.drawable.ic_assault
            "Bomber" -> R.drawable.ic_bomber
            "Helicopter" -> R.drawable.ic_helicopter

            "Ship", "TorpedoBoat" -> R.drawable.ic_boat
            "Frigate", "Gunboat" -> R.drawable.ic_frigate
            "Destroyer" -> R.drawable.ic_destroyer

            "Cruiser",
            "LightCruiser",
            "HeavyCruiser" -> R.drawable.ic_cruiser

            "Battleship",
            "Battlecruiser" -> R.drawable.ic_battleship

            "Player" -> R.drawable.ic_player
            "capture_zone" -> R.drawable.ic_capture_zone
            "airfield" -> R.drawable.ic_airfield

            else -> R.drawable.ic_light_tank
        }
    }
}