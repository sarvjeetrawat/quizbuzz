package com.kunpitech.quizbuzz.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore("user_prefs")

object UserPreferences {
    private val EMAIL_KEY = stringPreferencesKey("email")
    private val PASSWORD_KEY = stringPreferencesKey("password")

    suspend fun saveCredentials(context: Context, email: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[EMAIL_KEY] = email
            prefs[PASSWORD_KEY] = password
        }
    }

    suspend fun getCredentials(context: Context): Pair<String?, String?> {
        val prefs = context.dataStore.data.first()
        return Pair(prefs[EMAIL_KEY], prefs[PASSWORD_KEY])
    }

    suspend fun clearCredentials(context: Context) {
        context.dataStore.edit { it.clear() }
    }
}
