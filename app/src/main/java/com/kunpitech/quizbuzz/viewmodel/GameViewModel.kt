package com.kunpitech.quizbuzz.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.kunpitech.quizbuzz.data.model.Question
import com.kunpitech.quizbuzz.data.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GameViewModel : ViewModel() {
    fun joinGame(userId: String, onRoomJoined: (String) -> Unit) {
        GameRepository.joinGame(userId, onRoomJoined)
    }

        private val db = FirebaseFirestore.getInstance()

        private val _questions = MutableStateFlow<List<Question>>(emptyList())
        val questions: StateFlow<List<Question>> = _questions

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading

        fun loadQuestions() {
            db.collection("questions")
                .get()
                .addOnSuccessListener { result ->
                    val list = result.documents.mapNotNull { it.toObject(Question::class.java) }
                    _questions.value = list.shuffled() // randomize order
                    _isLoading.value = false
                }
                .addOnFailureListener {
                    _isLoading.value = false
                }
        }


    fun uploadQuestions() {
        val database = FirebaseDatabase.getInstance()
        val questionsRef = database.getReference("questions")

        val sampleQuestions = listOf(
            Question(
                id = "Q5",
                question = "Which Car logo is this?",
                options = listOf("Acura", "Audi", "Mercedes", "Jaguar"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/acura.jpg",
                answer = "Acura"
            ),
            Question(
                id = "Q6",
                question = "Which Car logo is this?",
                options = listOf("BMW", "Audi", "Geely", "Tesla"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/audi.jpg",
                answer = "Audi"
            ),
            Question(
                id = "Q7",
                question = "Which Car logo is this?",
                options = listOf("Mini", "Buick", "Honda", "Bently"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/bently.jpg",
                answer = "Bently"
            ),
            Question(
                id = "Q8",
                question = "Which Car logo is this?",
                options = listOf("Cadilac", "Lincoln", "Citroen", "Lexus"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/cadilac.jpg",
                answer = "Cadilac"
            ),
            Question(
                id = "Q9",
                question = "Which Car logo is this?",
                options = listOf("Cadilac", "Mayback", "Citroen", "Buick"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/buick.jpg",
                answer = "Buick"
            ),
            Question(
                id = "Q10",
                question = "Which Car logo is this?",
                options = listOf("Cadilac", "Mayback", "Citroen", "jaguar"),
                imageUrl = "https://raw.githubusercontent.com/sarvjeetrawat/quizzimages/main/images/citroen.jpg",
                answer = "Citroen"
            )
        )

        sampleQuestions.forEach { question ->
            // Use question.id as the key instead of push()
            questionsRef.child(question.id).setValue(question)
                .addOnSuccessListener {
                    Log.d("Upload", "Uploaded: ${question.id}")
                }
                .addOnFailureListener { e ->
                    Log.e("Upload", "Error uploading ${question.id}", e)
                }
        }

        // Optionally, set first question as currentQuestion
        val roomRef = database.getReference("rooms/room1") // replace with your roomId
        roomRef.child("currentQuestion").setValue("Q1")
    }


}