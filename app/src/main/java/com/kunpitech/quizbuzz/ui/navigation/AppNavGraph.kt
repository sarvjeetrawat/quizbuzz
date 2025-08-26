package com.kunpitech.quizbuzz.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kunpitech.quizbuzz.ui.screens.HomeScreen
import com.kunpitech.quizbuzz.ui.screens.LoginScreen
import com.kunpitech.quizbuzz.ui.screens.QuizScreen
import com.kunpitech.quizbuzz.ui.screens.SplashScreen
import java.util.UUID

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Home : Screen("home")
    object Quiz : Screen("quiz/{roomId}") {
        fun createRoute(roomId: String) = "quiz/$roomId"
    }
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(navController)
        }

        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Home.route) {
            HomeScreen(
                userName = "Sarvjeet", // later fetch from FirebaseAuth
                userId = "user_${UUID.randomUUID()}", // 🔹 replace with FirebaseAuth.uid
                onStartQuiz = { roomId ->
                    navController.navigate(Screen.Quiz.createRoute(roomId))
                },
                onProfileClick = { /* TODO: navigate to profile */ }
            )
        }

        composable(
            route = Screen.Quiz.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            QuizScreen(roomId = roomId)
        }
    }
}
