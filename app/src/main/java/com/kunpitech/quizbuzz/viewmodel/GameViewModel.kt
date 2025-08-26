package com.kunpitech.quizbuzz.viewmodel

import androidx.lifecycle.ViewModel
import com.kunpitech.quizbuzz.data.repository.GameRepository

class GameViewModel : ViewModel() {
    fun joinGame(userId: String, onRoomJoined: (String) -> Unit) {
        GameRepository.joinGame(userId, onRoomJoined)
    }
}