package com.ysar.stopfundwar.navigation

import com.ysar.stopfundwar.R

sealed class Screen(val route: String) {
    object Welcome : Screen(route = "welcome_screen")
    object Main : Screen(route = "home_screen")
}

sealed class BottomBarScreen(
    val route: String,
    val icon: Int,
) {
    object Camera : BottomBarScreen(
        route = "camera",
        icon = R.drawable.camera
    )

    object Charity : BottomBarScreen(
        route = "charity",
        icon = R.drawable.charity
    )
}