package com.kunpitech.quizbuzz.utils

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.InputStream
suspend fun uploadImageToGitHub(
    imageStream: InputStream,
    fileName: String,
    githubToken: String,
    repoName: String,
    userName: String
): String? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()

        // Convert image to Base64
        val imageBytes = imageStream.readBytes()
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val url = "https://api.github.com/repos/$userName/$repoName/contents/profile/$fileName"

        // Step 1: Check if file exists
        val getRequest = Request.Builder()
            .url(url)
            .header("Authorization", "token $githubToken")
            .get()
            .build()

        var sha: String? = null
        client.newCall(getRequest).execute().use { response ->
            if (response.isSuccessful) {
                val resp = response.body?.string()
                if (!resp.isNullOrEmpty()) {
                    val jsonResp = JSONObject(resp)
                    sha = jsonResp.getString("sha")
                }
            }
        }

        // Step 2: Prepare JSON with optional sha
        val json = JSONObject().apply {
            put("message", "Upload profile image $fileName")
            put("content", base64Image)
            sha?.let { put("sha", it) } // Only include sha if file exists
        }

        val body = RequestBody.create("application/json".toMediaType(), json.toString())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $githubToken")
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Upload failed: ${response.code}")
            val resp = response.body?.string()
            val jsonResp = JSONObject(resp!!)
            val downloadUrl = jsonResp.getJSONObject("content").getString("download_url")

            return@withContext downloadUrl
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }

    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}
