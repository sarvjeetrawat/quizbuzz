package com.kunpitech.quizbuzz.data.repository


import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.UUID

object GameRepository {

    fun joinGame(
        userId: String,
        onRoomJoined: (roomId: String) -> Unit
    ) {
        val db = FirebaseDatabase.getInstance().reference
        val waitingRef = db.child("waiting")
        val assignmentRef = db.child("waitingRoomAssignments").child(userId)

        // Step 1: Always start listening for a room assignment
        assignmentRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val roomId = snapshot.getValue(String::class.java)
                if (roomId != null) {
                    onRoomJoined(roomId)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Step 2: Try to match
        waitingRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // Someone is waiting → create room
                val otherPlayerId = snapshot.value.toString()
                val roomId = UUID.randomUUID().toString()

                val roomData = mapOf(
                    "player1" to otherPlayerId,
                    "player2" to userId,
                    "currentQuestion" to "Q1",
                    "scores" to mapOf(otherPlayerId to 0, userId to 0)
                )

                db.child("rooms").child(roomId).setValue(roomData)
                    .addOnSuccessListener {
                        // Assign both users
                        db.child("waitingRoomAssignments").child(otherPlayerId).setValue(roomId)
                        db.child("waitingRoomAssignments").child(userId).setValue(roomId)

                        waitingRef.removeValue()
                    }

            } else {
                // No one waiting → set myself as waiting
                waitingRef.setValue(userId)
            }
        }
    }
}
