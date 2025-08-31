package com.kunpitech.quizbuzz.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.firebase.database.FirebaseDatabase
import com.kunpitech.quizbuzz.ui.screens.HomeScreen
import com.kunpitech.quizbuzz.ui.screens.LoginScreen
import com.kunpitech.quizbuzz.ui.screens.ProfileScreen
import com.kunpitech.quizbuzz.ui.screens.QuizScreen
import com.kunpitech.quizbuzz.ui.screens.ResultScreen
import com.kunpitech.quizbuzz.ui.screens.SplashScreen
import com.kunpitech.quizbuzz.viewmodel.GameViewModel
import java.util.UUID
// --- Screens
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Home : Screen("home")
    object Quiz : Screen("quiz/{roomId}") {
        fun createRoute(roomId: String) = "quiz/$roomId"
    }
    object Result : Screen("result/{roomId}") {
        fun createRoute(roomId: String) = "result/$roomId"
    }

    object Profile : Screen("profile")
}

// --- NavGraph
@Composable
fun AppNavGraph(navController: NavHostController) {
    // keep a stable userId for this app session
    val userId = remember { "user_${UUID.randomUUID()}" }

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
                userName = "Sarvjeet",              // replace with FirebaseAuth later
                userId = userId,
                onStartQuiz = { roomId ->
                    navController.navigate(Screen.Quiz.createRoute(roomId))
                },
                navController = navController
            )
        }

        composable(
            route = Screen.Quiz.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            if (roomId.isEmpty()) {
                Text("Invalid Room ID", color = Color.Red)
            } else {
                QuizScreen(
                    roomId = roomId,
                    userId = userId,
                    // 🔹 when quiz finishes, go to Result screen
                    onNavigateToResult = {
                        navController.navigate(Screen.Result.createRoute(roomId)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.Result.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            ResultScreen(
                roomId = roomId,
                userId = userId,
                navController = navController
            )
        }

        // 🔹 Profile screen route
        composable(Screen.Profile.route) {
            ProfileScreen(
                onProfileSaved = {
                    navController.popBackStack()   // go back to Home after saving
                }
            )
        }
    }
}
