package com.kunpitech.quizbuzz.data.model

data class Question(
    val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val imageUrl: String = "",
    val answer: String = ""
)