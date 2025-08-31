package com.kunpitech.quizbuzz.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.kunpitech.quizbuzz.R
import com.kunpitech.quizbuzz.utils.uploadImageToGitHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@Composable
fun ProfileScreen(
    onProfileSaved: () -> Unit
) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid ?: return
    val usersRef = FirebaseDatabase.getInstance().getReference("users")
    val sharedPrefs = context.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)

    var username by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var profilePicUrl by remember { mutableStateOf<String?>(null) }
    var selectedDrawableRes by remember { mutableStateOf<Int?>(null) }
    var bitmapFromPrefs by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val drawableList = listOf(
        R.drawable.profile_1,
        R.drawable.profile_2,
        R.drawable.profile_3,
        R.drawable.profile_4,
        R.drawable.profile_5,
        R.drawable.profile_6,
        R.drawable.profile_7
    )

    // Load cached data
    LaunchedEffect(uid) {
        sharedPrefs.getString("profile_image_base64", null)?.let { base64 ->
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            bitmapFromPrefs = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        selectedDrawableRes = sharedPrefs.getInt("profile_drawable_res", -1).takeIf { it != -1 }

        usersRef.child(uid).get().addOnSuccessListener { snapshot ->
            username = snapshot.child("username").getValue(String::class.java) ?: username
            snapshot.child("profilePicUrl").getValue(String::class.java)?.let { firebaseUrl ->
                if (bitmapFromPrefs == null && selectedDrawableRes == null) profilePicUrl = firebaseUrl
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            imageUri = uri
            profilePicUrl = null
            selectedDrawableRes = null
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            "Edit Profile",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        // Profile Picture
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
        ) {
            Card(
                shape = CircleShape,
                modifier = Modifier.fillMaxSize(),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                when {
                    imageUri != null -> Image(
                        painter = rememberAsyncImagePainter(imageUri),
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize()
                    )
                    selectedDrawableRes != null -> Image(
                        painter = painterResource(selectedDrawableRes!!),
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize()
                    )
                    bitmapFromPrefs != null -> Image(
                        bitmap = bitmapFromPrefs!!.asImageBitmap(),
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize()
                    )
                    !profilePicUrl.isNullOrEmpty() -> Image(
                        painter = rememberAsyncImagePainter("${profilePicUrl}?time=${System.currentTimeMillis()}"),
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No Image", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Profile Image",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(6.dp)
                    .clickable { imagePicker.launch("image/*") }
            )
        }

        Spacer(Modifier.height(24.dp))

        // Drawable Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(100.dp)
        ) {
            items(drawableList.size) { index ->
                val resId = drawableList[index]
                Image(
                    painter = painterResource(resId),
                    contentDescription = "Drawable $index",
                    modifier = Modifier
                        .size(60.dp)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (selectedDrawableRes == resId) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            selectedDrawableRes = resId
                            imageUri = null
                            profilePicUrl = null
                        }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username (unique)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.isBlank()) {
                    errorMessage = "Please enter a username"
                    return@Button
                }

                loading = true

                // Check username uniqueness
                usersRef.orderByChild("username").equalTo(username).get()
                    .addOnSuccessListener { snapshot ->
                        if (snapshot.exists() && snapshot.child(uid).value == null) {
                            loading = false
                            errorMessage = "Username already taken"
                        } else {
                            when {
                                imageUri != null || selectedDrawableRes != null || bitmapFromPrefs != null -> {
                                    // Convert selected image to Bitmap
                                    val bitmap: Bitmap? = when {
                                        imageUri != null -> BitmapFactory.decodeStream(context.contentResolver.openInputStream(imageUri!!))
                                        selectedDrawableRes != null -> BitmapFactory.decodeResource(context.resources, selectedDrawableRes!!)
                                        bitmapFromPrefs != null -> bitmapFromPrefs
                                        else -> null
                                    }

                                    if (bitmap != null) {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val baos = ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                                            val githubUrl = uploadImageToGitHub(
                                                imageStream = baos.toByteArray().inputStream(),
                                                fileName = "${uid}.jpg",
                                                githubToken = "",
                                                repoName = "quizzimages",
                                                userName = "sarvjeetrawat"
                                            )

                                            if (githubUrl != null) {
                                                // Save bitmap locally
                                                val encoded = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
                                                sharedPrefs.edit().apply {
                                                    putString("profile_image_base64", encoded)
                                                    putString("username", username)
                                                    putInt("profile_drawable_res", selectedDrawableRes ?: -1)
                                                    apply()
                                                }

                                                saveProfile(uid, username, githubUrl, onProfileSaved, context)
                                            } else {
                                                loading = false
                                                errorMessage = "Image upload failed"
                                            }
                                        }
                                    } else {
                                        loading = false
                                        errorMessage = "Bitmap not found"
                                    }
                                }
                                profilePicUrl != null -> saveProfile(uid, username, profilePicUrl!!, onProfileSaved, context)
                                else -> {
                                    loading = false
                                    errorMessage = "Profile image not found"
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        loading = false
                        errorMessage = it.message
                    }
            },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(if (loading) "Saving..." else "Save Profile")
        }

        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun saveProfile(
    uid: String,
    username: String,
    profilePicUrl: String,
    onProfileSaved: () -> Unit,
    context: Context
) {
    val usersRef = FirebaseDatabase.getInstance().getReference("users")
    usersRef.child(uid).setValue(
        mapOf("username" to username, "profilePicUrl" to profilePicUrl)
    ).addOnSuccessListener {
        Toast.makeText(context, "Profile saved!", Toast.LENGTH_SHORT).show()
        onProfileSaved()
    }.addOnFailureListener {
        Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
    }
}
