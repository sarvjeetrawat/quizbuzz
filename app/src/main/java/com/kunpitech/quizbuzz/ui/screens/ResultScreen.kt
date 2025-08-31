package com.kunpitech.quizbuzz.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.database.*

import androidx.compose.ui.graphics.Brush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    roomId: String,
    userId: String,
    navController: NavController
) {
    val db = FirebaseDatabase.getInstance().reference
    var players by remember { mutableStateOf<Map<String, Triple<String, String?, Int>>>(emptyMap()) }

    // 🔹 Listen for userScore instead of scores
    DisposableEffect(roomId) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newPlayers = snapshot.children.associate { child ->
                    val uid = child.key!!
                    val username = child.child("username").getValue(String::class.java) ?: uid
                    val profilePicUrl = child.child("profilePicUrl").getValue(String::class.java)
                    val score = child.child("score").getValue(Int::class.java) ?: 0
                    uid to Triple(username, profilePicUrl, score)
                }
                players = newPlayers
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        db.child("rooms").child(roomId).child("userScore")
            .addValueEventListener(listener)

        onDispose {
            db.child("rooms").child(roomId).child("userScore")
                .removeEventListener(listener)
        }
    }

    val sortedScores = players.toList().sortedByDescending { it.second.third }  // sort by score
    val winner = sortedScores.firstOrNull()?.first
    val highestScore = sortedScores.firstOrNull()?.second?.third ?: 0
    val userScore = players[userId]?.third ?: 0
    val isTie = sortedScores.size > 1 && sortedScores.count { it.second.third == highestScore } > 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🏆 Results", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1976D2))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFBBDEFB), Color.White)
                    )
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // ---------- BIG PLAYER AVATARS ----------
            if (sortedScores.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sortedScores.forEach { (uid, data) ->
                        val (username, picUrl, score) = data

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.width(150.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(6.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                when {
                                    !picUrl.isNullOrEmpty() -> {
                                        Image(
                                            painter = rememberAsyncImagePainter(picUrl),
                                            contentDescription = "Avatar",
                                            modifier = Modifier
                                                .size(90.dp)
                                                .clip(CircleShape)
                                        )
                                    }
                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .size(90.dp)
                                                .clip(CircleShape)
                                                .background(Color.Gray),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                username.firstOrNull()?.uppercase() ?: "?",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 28.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = when {
                                        isTie && score == highestScore -> "🤝 $username"
                                        uid == winner -> "👑 $username"
                                        else -> username
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )

                                Text("Score: $score", fontSize = 16.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ---------- RESULT MESSAGE ----------
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your Score: $userScore", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when {
                        isTie -> "🤝 It's a Tie!"
                        winner == userId -> "🎉 You are the Winner!"
                        else -> "😢 You Lost!"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        winner == userId -> Color(0xFF388E3C)
                        else -> Color(0xFFD32F2F)
                    }
                )
            }

            Spacer(Modifier.weight(1f))

            // ---------- ACTION BUTTONS ----------
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { navController.navigate("quiz/$roomId/$userId") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Play Again", color = Color.White)
                }
                Button(
                    onClick = {
                        db.child("waitingRoomAssignments").child(userId).removeValue()
                        db.child("waiting").child(userId).removeValue()
                        db.child("rooms").child(roomId).removeValue()

                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Exit", color = Color.White)
                }
            }
        }
    }
}


