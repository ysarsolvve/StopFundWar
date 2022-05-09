package com.ysar.stopfundwar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.pager.ExperimentalPagerApi
import com.ysar.stopfundwar.navigation.SetupNavGraph
import com.ysar.stopfundwar.ui.theme.OnBoardingComposeTheme
import com.ysar.stopfundwar.util.Yolov5TFLiteDetector
import com.ysar.stopfundwar.viewmodel.SplashViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@ExperimentalAnimationApi
@ExperimentalPagerApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var splashViewModel: SplashViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen().setKeepOnScreenCondition {
            !splashViewModel.isLoading.value
        }
            setContent {
                OnBoardingComposeTheme {
                    val screen by splashViewModel.startDestination
                    val navController = rememberNavController()
                    if (screen == "home_screen") {
                        MainScreen()
                    } else SetupNavGraph(navController = navController, startDestination = screen)
                }
            }
    }
}