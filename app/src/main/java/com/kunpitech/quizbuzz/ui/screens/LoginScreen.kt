package com.kunpitech.quizbuzz.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.kunpitech.quizbuzz.data.local.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val deviceId = remember { UUID.randomUUID().toString() }

    // Load saved credentials once
    LaunchedEffect(Unit) {
        val (savedEmail, savedPassword) = UserPreferences.getCredentials(context)
        if (!savedEmail.isNullOrBlank() && !savedPassword.isNullOrBlank()) {
            email = savedEmail
            password = savedPassword
            // 🔹 Auto-login if you want:
            loading = true
            auth.signInWithEmailAndPassword(savedEmail, savedPassword)
                .addOnCompleteListener { task ->
                    loading = false
                    if (task.isSuccessful) {
                        onLoginSuccess()
                    }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("QuizBuzz Login", style = MaterialTheme.typography.headlineLarge)

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    errorMessage = "Please enter both email and password"
                    return@Button
                }

                loading = true
                auth.signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            val uid = user?.uid ?: ""

                            val userRef = FirebaseDatabase.getInstance()
                                .getReference("users")
                                .child(uid)
                                .child("activeDevice")

                            userRef.get().addOnSuccessListener { snapshot ->
                                val activeDevice = snapshot.getValue(String::class.java)

                                if (activeDevice != null && activeDevice != deviceId) {
                                    // ❌ Already logged in elsewhere
                                    auth.signOut()
                                    loading = false
                                    errorMessage = "This user is already logged in on another device"
                                } else {
                                    // ✅ Allow login and set this device as active
                                    userRef.setValue(deviceId)

                                    CoroutineScope(Dispatchers.IO).launch {
                                        UserPreferences.saveCredentials(context, email, password)
                                    }

                                    loading = false
                                    onLoginSuccess()
                                }
                            }
                        } else {
                            loading = false
                            errorMessage = task.exception?.message ?: "Login failed"
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            Text(if (loading) "Logging in..." else "Login")
        }

        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color.Red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

