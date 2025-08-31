package com.kunpitech.quizbuzz.ui.screens


import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kunpitech.quizbuzz.R
import com.kunpitech.quizbuzz.data.repository.GameRepository.joinGame
import com.kunpitech.quizbuzz.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userName: String = "Player",
    userId: String,
    onStartQuiz: (roomId: String) -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    var isWaiting by remember { mutableStateOf(false) }
    var waitingMessage by remember { mutableStateOf("Start Quiz") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("QuizBuzz") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // Logo
            Image(
                painter = painterResource(id = R.drawable.quizbuzz),
                contentDescription = "App Logo",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Welcome Text
            Text(
                text = "Welcome, $userName 👋",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Start Quiz / Waiting Button
            Button(
                onClick = {
                    if (!isWaiting) {
                        isWaiting = true
                        waitingMessage = "Waiting for another player..."
                        joinGame(userId) { roomId ->
                            isWaiting = false
                            waitingMessage = "Start Quiz"
                            onStartQuiz(roomId) // Navigate only when room is assigned
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                enabled = !isWaiting
            ) {
                if (isWaiting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = waitingMessage, fontSize = 16.sp)
                    }
                } else {
                    Text(text = waitingMessage, fontSize = 18.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Profile Button
            OutlinedButton(
                onClick = { navController.navigate(Screen.Profile.route) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Text(text = "Profile")
            }
        }
    }
}


