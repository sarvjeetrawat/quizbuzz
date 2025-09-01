package com.kunpitech.quizbuzz.ui.screens

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val QUESTION_DURATION_SEC = 10L
private const val SHOW_RESULT_MS = 6000L
private const val NEXT_QUESTION_WAIT_SEC = 5 // 5 second visible countdown overlay

// -------------------------
// Helpers (top-level so no unresolved references)
// -------------------------
fun tryAdvanceWithToken(roomRef: DatabaseReference, qId: String, onLeader: () -> Unit) {
    val tokenRef = roomRef.child("advanceToken")
    tokenRef.runTransaction(object : Transaction.Handler {
        override fun doTransaction(currentData: MutableData): Transaction.Result {
            val token = currentData.getValue(String::class.java)
            return if (token == null) {
                // claim token for this qId
                currentData.value = qId
                Transaction.success(currentData)
            } else {
                Transaction.abort()
            }
        }

        override fun onComplete(error: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
            if (error != null) Log.e("QuizScreen", "advanceToken tx error: ${error.message}")
            if (committed) {
                // We are the leader for this advance
                onLeader()
            }
        }
    })
}

fun incrementScore(roomRef: DatabaseReference, winnerId: String) {
    val scoreRef = roomRef.child("userScore").child(winnerId).child("score")
    scoreRef.runTransaction(object : Transaction.Handler {
        override fun doTransaction(currentData: MutableData): Transaction.Result {
            val cur = currentData.getValue(Int::class.java) ?: 0
            currentData.value = cur + 1
            return Transaction.success(currentData)
        }

        override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
            if (error != null) Log.e("QuizScreen", "userScore tx error for $winnerId: ${error.message}")
        }
    })
}

fun advanceNext(scope: CoroutineScope, roomRef: DatabaseReference, questionOrder: List<String>, qId: String) {
    val currentIndex = questionOrder.indexOf(qId)
    val nextQ = if (currentIndex != -1 && currentIndex < questionOrder.size - 1)
        questionOrder[currentIndex + 1]
    else
        "finished"

    scope.launch {
        delay(SHOW_RESULT_MS) // let clients show result briefly
        // clear ephemeral nodes and move to next question
        roomRef.child("answers").removeValue()
        roomRef.child("result").setValue(null)
        roomRef.child("questionDeadline").removeValue()
        roomRef.child("currentQuestion").setValue(nextQ)
        roomRef.child("advanceToken").setValue(null)
    }
}

// -------------------------
// Composable QuizScreen
// -------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    roomId: String,
    userId: String = "user_${UUID.randomUUID()}",
    onNavigateToResult: () -> Unit = {}
) {
    val db = FirebaseDatabase.getInstance().reference
    val roomRef = db.child("rooms").child(roomId)

    // -------------------------
    // UI state
    // -------------------------
    var currentQuestionId by remember { mutableStateOf<String?>(null) }
    var questionText by remember { mutableStateOf("Loading question...") }
    var options by remember { mutableStateOf(listOf<String>()) }
    var correctAnswer by remember { mutableStateOf<String?>(null) }
    var selectedOption by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf("") }
    var timeLeft by remember { mutableStateOf(QUESTION_DURATION_SEC.toInt()) }
    var imageUrl by remember { mutableStateOf<String?>(null) }

    var nextQuestionId by remember { mutableStateOf<String?>(null) }

    // server-truth Q list (10)
    val questionOrder = remember { mutableStateListOf<String>() }

    // shared deadline millis for the current question
    var questionDeadlineMillis by remember { mutableStateOf<Long?>(null) }

    val scope = rememberCoroutineScope()
    var timerJob by remember { mutableStateOf<Job?>(null) }
    var watchdogJob by remember { mutableStateOf<Job?>(null) }

    // NEXT question overlay state
    var showNextCountdown by remember { mutableStateOf(false) }
    var nextCountdownValue by remember { mutableStateOf(NEXT_QUESTION_WAIT_SEC) }
    var nextCountdownJob by remember { mutableStateOf<Job?>(null) }
    val nextNumberScale = remember { Animatable(1f) }

    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid ?: return

    // Register player presence on join; remove on dispose
    DisposableEffect(roomId) {
        roomRef.child("players").child(userId).setValue(true)

        // ⭐ Fetch username & profilePicUrl from Firebase `users/{uid}`
        val usersRef = db.child("users").child(uid)
        usersRef.get().addOnSuccessListener { snapshot ->
            val fetchedUsername = snapshot.child("username").getValue(String::class.java) ?: "Player"
            val fetchedProfilePicUrl = snapshot.child("profilePicUrl").getValue(String::class.java) ?: "https://example.com/default.png"

            // ⭐ Save user info inside "userScore" node
            val userData = mapOf(
                "score" to 0,
                "username" to fetchedUsername,
                "profilePicUrl" to fetchedProfilePicUrl
            )
            roomRef.child("userScore").child(userId).updateChildren(userData)
        }

        onDispose {
            timerJob?.cancel()
            watchdogJob?.cancel()
            nextCountdownJob?.cancel()
            roomRef.child("players").child(userId).removeValue()
        }
    }

    // -------------------------
    // 1) Initialize questionOrder/currentQuestion & nodes (transactional)
    // -------------------------
    LaunchedEffect(roomId) {
        val questionsRef = db.child("questions")
        questionsRef.get().addOnSuccessListener { snapshot ->
            val allIds = snapshot.children.mapNotNull { it.key }
            if (allIds.isEmpty()) {
                Log.e("QuizScreen", "No questions in DB")
                return@addOnSuccessListener
            }
            val shuffledList = allIds.shuffled().take(10)

            roomRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (!currentData.hasChild("questionOrder")) {
                        currentData.child("questionOrder").value = shuffledList
                    }
                    val existingCurrent = currentData.child("currentQuestion").getValue(String::class.java)
                    if (existingCurrent.isNullOrEmpty()) {
                        val order = (currentData.child("questionOrder").value as? List<*>)?.filterIsInstance<String>()
                        val first = order?.firstOrNull() ?: shuffledList.first()
                        currentData.child("currentQuestion").value = first
                    }
                    if (!currentData.hasChild("advanceToken")) {
                        currentData.child("advanceToken").value = null
                    }
                    if (!currentData.hasChild("scores")) {
                        currentData.child("scores").value = HashMap<String, Int>() // keep old scores for safety
                    }

                    return Transaction.success(currentData)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                    if (error != null) Log.e("QuizScreen", "Init tx failed: ${error.message}")
                }
            })
        }.addOnFailureListener { Log.e("QuizScreen", "Failed to load questions: ${it.message}") }
    }

    // -------------------------
    // 2) Listen for questionOrder (server-truth)
    // -------------------------
    DisposableEffect(roomId) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { it.getValue(String::class.java) }
                questionOrder.clear(); questionOrder.addAll(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        roomRef.child("questionOrder").addValueEventListener(l)
        onDispose { roomRef.child("questionOrder").removeEventListener(l) }
    }

    // -------------------------
    // Helper: start local timer from a deadline (millis)
    // -------------------------
    fun startLocalTimerFrom(deadlineMillis: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = deadlineMillis - now
                val remainingSec = ((remainingMs + 999) / 1000).coerceAtLeast(0L).toInt()
                timeLeft = remainingSec
                if (remainingMs <= 0L) break
                delay(200L)
            }

            // At deadline, if this user hasn't answered, write TIME_UP
            if (selectedOption == null) {
                selectedOption = "TIME_UP"
                resultText = "⏱ Time's up!"
                val map = mapOf("option" to "TIME_UP", "ts" to System.currentTimeMillis())
                roomRef.child("answers").child(userId).setValue(map)
            }
        }
    }

    // -------------------------
    // Helper: ensure shared question deadline exists (transactional)
    // -------------------------
    fun ensureQuestionDeadline(qId: String) {
        val desiredDeadline = System.currentTimeMillis() + QUESTION_DURATION_SEC * 1000L
        val deadlineRef = roomRef.child("questionDeadline")
        deadlineRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (currentData.value == null) {
                    currentData.value = desiredDeadline
                }
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                if (error != null) {
                    Log.e("QuizScreen", "deadline tx error: ${error.message}")
                    // fallback: start local with desired
                    questionDeadlineMillis = desiredDeadline
                    startLocalTimerFrom(desiredDeadline)
                    return
                }
                // use value from DB (snap) if available
                val finalVal = snap?.getValue(Long::class.java) ?: desiredDeadline
                questionDeadlineMillis = finalVal
                startLocalTimerFrom(finalVal)
            }
        })
    }

    // -------------------------
    // 3) Listen for currentQuestion -> load content & start shared deadline + watchdog
    // -------------------------
    DisposableEffect(roomId) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val qId = snapshot.getValue(String::class.java)
                if (qId == "finished") {
                    // tiny buffer then navigate to result screen
                    scope.launch { delay(200); onNavigateToResult() }
                    return
                }

                if (qId != null && qId != currentQuestionId) {
                    // server moved to next question -> hide overlay immediately
                    showNextCountdown = false
                    nextCountdownJob?.cancel()
                    nextCountdownValue = NEXT_QUESTION_WAIT_SEC
                    scope.launch {
                        nextNumberScale.snapTo(1f)
                    }

                    currentQuestionId = qId

                    // reset UI immediately (avoid flicker)
                    selectedOption = null
                    resultText = ""
                    timeLeft = QUESTION_DURATION_SEC.toInt()
                    timerJob?.cancel()
                    watchdogJob?.cancel()
                    questionDeadlineMillis = null

                    // load question content and start shared deadline
                    db.child("questions").child(qId).get()
                        .addOnSuccessListener { qSnap ->
                            questionText = qSnap.child("question").getValue(String::class.java) ?: "No question found"
                            options = qSnap.child("options").children.mapNotNull { it.getValue(String::class.java) }
                            correctAnswer = qSnap.child("answer").getValue(String::class.java)
                            imageUrl = qSnap.child("imageUrl").getValue(String::class.java)

                            // ensure shared deadline & start local timer
                            ensureQuestionDeadline(qId)

                            // watchdog: if nobody wrote answers at all (network / bug), leader forces advance after deadline+buffer
                            watchdogJob = scope.launch {
                                delay((QUESTION_DURATION_SEC * 1000L) + 500L)
                                roomRef.child("answers").get().addOnSuccessListener { ansSnap ->
                                    val anyAnswers = ansSnap.exists() && ansSnap.childrenCount > 0L
                                    if (!anyAnswers) {
                                        // try to advance (no_one)
                                        tryAdvanceWithToken(roomRef, qId) {
                                            // persist nothing (nobody answered), set result and advance
                                            roomRef.child("result").setValue("no_one")
                                            // write answersHistory as TIME_UP for all players to record missed answers
                                            roomRef.child("players").get().addOnSuccessListener { pSnap ->
                                                val players = pSnap.children.mapNotNull { it.key }
                                                players.forEach { uid ->
                                                    roomRef.child("answersHistory").child(uid).child(qId).setValue("TIME_UP")
                                                }
                                                advanceNext(scope, roomRef, questionOrder, qId)
                                            }.addOnFailureListener { e ->
                                                Log.e("QuizScreen", "players.get failed: ${e.message}")
                                                advanceNext(scope, roomRef, questionOrder, qId)
                                            }
                                        }
                                    }
                                }.addOnFailureListener { e ->
                                    Log.e("QuizScreen", "watchdog answers.get failed: ${e.message}")
                                }
                            }
                        }
                        .addOnFailureListener {
                            questionText = "Error loading question"
                            Log.e("QuizScreen", "Load q failed: ${it.message}")
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        roomRef.child("currentQuestion").addValueEventListener(l)
        onDispose { roomRef.child("currentQuestion").removeEventListener(l) }
    }

    // -------------------------
    // 4) Listen for result (per-player message)
    //    Cancel local timer when result arrives so it doesn't race and write TIME_UP after result
    //    Also start the 5s overlay countdown for players
    // -------------------------
    DisposableEffect(roomId) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val v = snapshot.getValue(String::class.java)
                resultText = when (v) {
                    null -> ""
                    "no_one" -> "No one answered correctly."
                    else -> if (v == userId) "🎉 You answered correctly!" else "❌ Opponent answered correctly!"
                }
                // stop local timer so it doesn't race
                if (v != null) timerJob?.cancel()

                // Start the visible 5s countdown overlay on all clients when a result exists
                if (v != null) {
                    // cancel any existing overlay job
                    nextCountdownJob?.cancel()
                    scope.launch {
                        nextNumberScale.snapTo(1f)
                    }
                    nextCountdownValue = NEXT_QUESTION_WAIT_SEC
                    showNextCountdown = true

                    // store next QID to switch later
                    val currentIndex = questionOrder.indexOf(currentQuestionId)
                    nextQuestionId = if (currentIndex != -1 && currentIndex < questionOrder.size - 1)
                        questionOrder[currentIndex + 1]
                    else "finished"

                    nextCountdownJob = scope.launch {
                        while (nextCountdownValue > 0 && showNextCountdown) {
                            nextNumberScale.animateTo(1.25f, tween(160))
                            nextNumberScale.animateTo(1f, tween(180))
                            delay(1000)
                            nextCountdownValue--
                        }
                        showNextCountdown = false

                        // now switch to the next question
                        currentQuestionId = nextQuestionId
                        nextQuestionId = null
                    }
                }
                else {
                    // server cleared result -> hide overlay
                    showNextCountdown = false
                    nextCountdownJob?.cancel()
                    nextCountdownValue = NEXT_QUESTION_WAIT_SEC
                    scope.launch {
                        nextNumberScale.snapTo(1f)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        roomRef.child("result").addValueEventListener(l)
        onDispose { roomRef.child("result").removeEventListener(l) }
    }

    // -------------------------
    // 5) Answers listener — core logic
    //    - If ANY correct answer exists => earliest correct wins immediately
    //    - Else if all players answered (including TIME_UP) => advance with no_one
    // -------------------------
    DisposableEffect(roomId) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val qId = currentQuestionId ?: return
                if (qId == "finished") return
                if (questionOrder.isEmpty()) return

                // parse answers: uid -> Pair(option, ts)
                val answersMap: Map<String, Pair<String, Long>> = snapshot.children.mapNotNull { child ->
                    val uid = child.key ?: return@mapNotNull null
                    val optionFromChild = child.child("option").getValue(String::class.java)
                    val option = optionFromChild ?: child.getValue(String::class.java) ?: "TIME_UP"
                    val ts = child.child("ts").getValue(Long::class.java)
                        ?: child.child("ts").getValue(Int::class.java)?.toLong()
                        ?: System.currentTimeMillis()
                    uid to (option to ts)
                }.toMap()

                // 1) Any correct answers?
                val correctEntries = answersMap.filterValues { it.first == correctAnswer }
                if (correctEntries.isNotEmpty()) {
                    val winnerId = correctEntries.minByOrNull { it.value.second }!!.key

                    tryAdvanceWithToken(roomRef, qId) {
                        // leader: persist answersHistory for everyone (fill TIME_UP for missing)
                        roomRef.child("players").get().addOnSuccessListener { pSnap ->
                            val allPlayers = pSnap.children.mapNotNull { it.key }
                            allPlayers.forEach { uid ->
                                val ans = answersMap[uid]?.first ?: "TIME_UP"
                                roomRef.child("answersHistory").child(uid).child(qId).setValue(ans)
                            }

                            // set result, increment score
                            roomRef.child("result").setValue(winnerId)
                            incrementScore(roomRef, winnerId)

                            // advance to next
                            advanceNext(scope, roomRef, questionOrder, qId)
                        }.addOnFailureListener { e ->
                            Log.e("QuizScreen", "players.get failed while persisting history: ${e.message}")
                            // still continue: set result & increment
                            roomRef.child("result").setValue(winnerId)
                            incrementScore(roomRef, winnerId)
                            advanceNext(scope, roomRef, questionOrder, qId)
                        }
                    }
                    return
                }

                // 2) No correct answers present -> check if all players have answered (including TIME_UP)
                roomRef.child("players").get().addOnSuccessListener { pSnap ->
                    val allPlayers = pSnap.children.mapNotNull { it.key }
                    if (allPlayers.isNotEmpty()) {
                        val allDone = allPlayers.all { answersMap.containsKey(it) }
                        if (allDone) {
                            tryAdvanceWithToken(roomRef, qId) {
                                // persist answers (including TIME_UP)
                                allPlayers.forEach { uid ->
                                    val ans = answersMap[uid]?.first ?: "TIME_UP"
                                    roomRef.child("answersHistory").child(uid).child(qId).setValue(ans)
                                }
                                roomRef.child("result").setValue("no_one")
                                advanceNext(scope, roomRef, questionOrder, qId)
                            }
                        } else {
                            // not all done yet -> wait (watchdog will fire if nobody answered)
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e("QuizScreen", "players.get failed: ${e.message}")
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        roomRef.child("answers").addValueEventListener(l)
        onDispose { roomRef.child("answers").removeEventListener(l) }
    }

    // -------------------------
    // 7) UI
    // -------------------------
    Scaffold(
        topBar = { TopAppBar(title = { Text("Room: $roomId") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (!showNextCountdown) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Timer
                Text(
                    text = "⏱ $timeLeft s left",
                    fontSize = 18.sp,
                    color = if (timeLeft <= 3) Color.Red else Color.Black,
                )

                // optional image
                imageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "Question Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(bottom = 16.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Question
                Text(questionText, fontSize = 22.sp, modifier = Modifier.padding(bottom = 24.dp))

                // Options
                options.forEach { option ->
                    val isAnswered = selectedOption != null || timeLeft == 0
                    val bgColor = when {
                        // show correct for everyone when round resolved (or when local flagged answered)
                        (isAnswered && option == correctAnswer) -> Color(0xFF4CAF50).copy(alpha = 0.85f)
                        (isAnswered && selectedOption == option && option != correctAnswer && selectedOption != "TIME_UP") ->
                            Color(0xFFF44336).copy(alpha = 0.85f)

                        else -> Color.Gray
                    }

                    Button(
                        onClick = {
                            // local guard: prevent multiple clicks or after timeout
                            if (selectedOption != null || timeLeft == 0) return@Button
                            // mark local selection
                            selectedOption = option
                            // stop local timer so we don't write TIME_UP after selecting
                            timerJob?.cancel()

                            // write ephemeral answer to shared /answers
                            val answerMap =
                                mapOf("option" to option, "ts" to System.currentTimeMillis())
                            roomRef.child("answers").child(userId).setValue(answerMap)
                                .addOnFailureListener { e ->
                                    Log.e(
                                        "QuizScreen",
                                        "Failed to write answer: ${e.message}"
                                    )
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

                Spacer(Modifier.height(24.dp))

                if (resultText.isNotEmpty()) {
                    Text(
                        text = resultText,
                        fontSize = 20.sp,
                        color = if (resultText.contains("🎉")) Color(0xFF4CAF50) else Color(
                            0xFFF44336
                        )
                    )
                }
            }
        }

            // -------------------------
            // Countdown overlay (centered)
            // -------------------------
            if (showNextCountdown) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .wrapContentSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E88E5), Color(0xFF1976D2))
                                )
                            )
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // animated big number
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${nextCountdownValue}",
                                fontSize = 56.sp,
                                color = Color.White,
                                modifier = Modifier
                                    .graphicsLayer(
                                        scaleX = nextNumberScale.value,
                                        scaleY = nextNumberScale.value
                                    )
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "Next question in ${nextCountdownValue}s",
                            fontSize = 16.sp,
                            color = Color.White
                        )

                        Spacer(Modifier.height(10.dp))

                        // progress ring
                        CircularProgressIndicator(
                        progress = { (nextCountdownValue.toFloat() / NEXT_QUESTION_WAIT_SEC.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                        strokeWidth = 6.dp,
                        trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
                        strokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
                        )
                    }
                }
            }
        }
    }
}
