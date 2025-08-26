package com.kunpitech.quizbuzz.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    roomId: String,
    userId: String = "user_${UUID.randomUUID()}"
) {
    val db = FirebaseDatabase.getInstance().reference
    val roomRef = db.child("rooms").child(roomId)

    // UI state
    var currentQuestionId by remember { mutableStateOf<String?>(null) }
    var questionText by remember { mutableStateOf("Loading question...") }
    var options by remember { mutableStateOf(listOf<String>()) }
    var correctAnswer by remember { mutableStateOf<String?>(null) }
    var selectedOption by remember { mutableStateOf<String?>(null) } // user's selection or "TIME_UP"
    var resultText by remember { mutableStateOf("") }
    var timeLeft by remember { mutableStateOf(10) }

    // helper state to avoid advancing same question multiple times
    var lastAdvancedQuestion by remember { mutableStateOf<String?>(null) }
    var answersCount by remember { mutableStateOf(0) }

    val coroutineScope = rememberCoroutineScope()

    // -------------------------
    // Listen for currentQuestion
    // -------------------------
    DisposableEffect(roomId) {
        val qListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val qId = snapshot.getValue(String::class.java)
                currentQuestionId = qId
                if (qId != null) {
                    // map question id -> text/options/correctAnswer
                    questionText = when (qId) {
                        "Q1" -> "What is the capital of India?"
                        "Q2" -> "Which planet is known as the Red Planet?"
                        else -> "Unknown Question"
                    }
                    options = when (qId) {
                        "Q1" -> listOf("Delhi", "Mumbai", "Chennai", "Kolkata")
                        "Q2" -> listOf("Mars", "Venus", "Jupiter", "Saturn")
                        else -> listOf("Option1", "Option2", "Option3", "Option4")
                    }
                    correctAnswer = when (qId) {
                        "Q1" -> "Delhi"
                        "Q2" -> "Mars"
                        else -> null
                    }
                    // reset per-question UI state
                    selectedOption = null
                    resultText = ""
                    timeLeft = 10
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        roomRef.child("currentQuestion").addValueEventListener(qListener)
        onDispose {
            roomRef.child("currentQuestion").removeEventListener(qListener)
        }
    }

    // -------------------------
    // Listen for result updates (explicit handling)
    // -------------------------
    DisposableEffect(roomId) {
        val rListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val resultValue = snapshot.getValue(String::class.java)
                // Explicitly handle special values
                resultText = when (resultValue) {
                    null -> "" // no result
                    "no_one" -> "No one answered correctly."
                    else -> {
                        if (resultValue == userId) "🎉 You answered correctly!"
                        else "❌ Opponent answered correctly!"
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        roomRef.child("result").addValueEventListener(rListener)
        onDispose {
            roomRef.child("result").removeEventListener(rListener)
        }
    }

    // -------------------------
    // Listen for answers count (to auto-advance when both answered)
    // -------------------------
    DisposableEffect(roomId) {
        val aListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                answersCount = snapshot.childrenCount.toInt()
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        roomRef.child("answers").addValueEventListener(aListener)
        onDispose {
            roomRef.child("answers").removeEventListener(aListener)
        }
    }

    // -------------------------
    // Timer: 10s per question
    // -------------------------
    LaunchedEffect(currentQuestionId) {
        // reset timer each question
        timeLeft = 10
        while (timeLeft > 0 && selectedOption == null) {
            delay(1000)
            timeLeft--
        }
        if (timeLeft == 0 && selectedOption == null) {
            selectedOption = "TIME_UP"
            resultText = "⏱ Time's up!"
            // write TIME_UP to DB so answers count increments and triggers advance
            roomRef.child("answers").child(userId).setValue("TIME_UP")
        }
    }

    // -------------------------
    // When answersCount >= 2 -> evaluate and advance question
    // -------------------------
    LaunchedEffect(answersCount, currentQuestionId) {
        val qId = currentQuestionId ?: return@LaunchedEffect
        if (answersCount >= 2 && lastAdvancedQuestion != qId) {
            // avoid double-advance for same question
            lastAdvancedQuestion = qId

            // evaluate answers
            roomRef.child("answers").get().addOnSuccessListener { snapshot ->
                val answersMap = mutableMapOf<String, String?>()
                for (child in snapshot.children) {
                    answersMap[child.key ?: ""] = child.getValue(String::class.java)
                }

                // determine who answered correctly
                val correctUsers = answersMap.filterValues { it == correctAnswer }.keys.toList()

                when {
                    correctUsers.size == 1 -> {
                        // single correct -> set that user as winner
                        roomRef.child("result").setValue(correctUsers.first())
                    }
                    correctUsers.size > 1 -> {
                        // multiple correct -> pick earliest if available (not implemented), else pick first
                        roomRef.child("result").get().addOnSuccessListener { resSnap ->
                            val existing = resSnap.getValue(String::class.java)
                            if (existing == null) roomRef.child("result").setValue(correctUsers.first())
                        }
                    }
                    else -> {
                        // none correct -> mark explicitly
                        roomRef.child("result").setValue("no_one")
                    }
                }

                // compute next question id
                val nextQ = when (qId) {
                    "Q1" -> "Q2"
                    "Q2" -> "Q3"
                    "Q3" -> "Q4"
                    "Q4" -> "Q5"
                    else -> null
                }

                // show result for 1.5s then clear and advance.
                coroutineScope.launch {
                    delay(1500)
                    // FIRST clear answers/result so clients will see a clean state when question changes
                    roomRef.child("answers").removeValue()
                    roomRef.child("result").removeValue()

                    // THEN set the next question
                    if (nextQ != null) {
                        roomRef.child("currentQuestion").setValue(nextQ)
                    } else {
                        roomRef.child("currentQuestion").setValue("finished")
                    }
                }
            }
        }
    }

    // -------------------------
    // UI
    // -------------------------
    Scaffold(
        topBar = { TopAppBar(title = { Text("Room: $roomId") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Timer
            Text(
                text = "⏱ $timeLeft s left",
                fontSize = 18.sp,
                color = if (timeLeft <= 3) Color.Red else Color.Black,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Question
            Text(
                text = questionText,
                fontSize = 22.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Options
            options.forEach { option ->
                val isAnswered = selectedOption != null || timeLeft == 0
                val bgColor = when {
                    isAnswered && option == correctAnswer -> Color(0xFF4CAF50).copy(alpha = 0.85f) // correct = green
                    isAnswered && selectedOption == option && option != correctAnswer && selectedOption != "TIME_UP" ->
                        Color(0xFFF44336).copy(alpha = 0.85f) // wrong = red
                    else -> Color.Gray // neutral
                }

                Button(
                    onClick = {
                        // guard: don't allow click if already answered or time up
                        if (selectedOption != null || timeLeft == 0) return@Button

                        selectedOption = option
                        // save selection to firebase
                        roomRef.child("answers").child(userId).setValue(option)

                        // if correct, set immediate result (fastest-first)
                        if (option == correctAnswer) {
                            roomRef.child("result").setValue(userId)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    enabled = selectedOption == null && timeLeft > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = bgColor,
                        disabledContainerColor = bgColor,
                        contentColor = Color.White
                    )
                ) {
                    Text(option, fontSize = 18.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Result text
            if (resultText.isNotEmpty()) {
                Text(
                    text = resultText,
                    fontSize = 20.sp,
                    color = if (resultText.contains("🎉")) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }
    }
}
