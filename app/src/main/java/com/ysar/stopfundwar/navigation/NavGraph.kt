package com.ysar.stopfundwar.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ysar.stopfundwar.screen.CompaniesScreen
import com.ysar.stopfundwar.screen.WelcomeScreen
import com.google.accompanist.pager.ExperimentalPagerApi
import com.ysar.stopfundwar.MainScreen
import com.ysar.stopfundwar.screen.CameraScreen
import com.ysar.stopfundwar.screen.CharityScreen

@ExperimentalAnimationApi
@ExperimentalPagerApi
@Composable
fun SetupNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = Screen.Welcome.route) {
            WelcomeScreen(navController = navController)
        }
        composable(route = Screen.Main.route) {
            MainScreen()
        }
    }
}

@Composable
fun BottomNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = BottomBarScreen.Companies.route
    ) {
        composable(route = BottomBarScreen.Companies.route) {
            CompaniesScreen()
        }
        composable(route = BottomBarScreen.Camera.route) {
            CameraScreen()
        }
        composable(route = BottomBarScreen.Charity.route) {
            CharityScreen()
        }
    }
}