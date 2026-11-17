package com.example.warthundermapdraw

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlin.random.Random

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "WarThunderPreferences"
        private const val KEY_USERNAME = "username"
        private const val DEFAULT_USERNAME = "User"
        private const val DATABASE_URL =
            "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"
    }

    private lateinit var usernameButton: TextView
    private lateinit var createSessionButton: Button
    private lateinit var joinSessionButton: Button

    private val auth = FirebaseAuth.getInstance()

    private val database =
        FirebaseDatabase.getInstance(DATABASE_URL).reference

    private var username: String = DEFAULT_USERNAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home)

        applySystemInsets()
        initializeViews()
        loadUsername()
        updateUsernameButton()
        initializeButtons()
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.HomeRoot)

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun initializeViews() {
        usernameButton = findViewById(R.id.UsernameButton)
        createSessionButton = findViewById(R.id.CreateSessionButton)
        joinSessionButton = findViewById(R.id.JoinSessionButton)
    }

    private fun initializeButtons() {
        usernameButton.setOnClickListener {
            showUsernameDialog()
        }

        createSessionButton.setOnClickListener {
            createSession()
        }

        joinSessionButton.setOnClickListener {
            showJoinSessionDialog()
        }
    }

    private fun loadUsername() {
        val preferences = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        val savedUsername = preferences.getString(KEY_USERNAME, null)

        username = if (savedUsername.isNullOrBlank()) {
            generateRandomUsername().also {
                preferences.edit()
                    .putString(KEY_USERNAME, it)
                    .apply()
            }
        } else {
            savedUsername
        }
    }

    private fun generateRandomUsername(): String {
        val number = Random.nextInt(1000, 10000)
        return "$DEFAULT_USERNAME$number"
    }

    private fun saveUsername(newUsername: String) {
        username = newUsername

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).edit()
            .putString(KEY_USERNAME, username)
            .apply()

        updateUsernameButton()
    }

    private fun updateUsernameButton() {
        usernameButton.text = username
    }

    private fun showUsernameDialog() {
        val input = EditText(this).apply {
            setText(username)
            setSelection(text.length)
            hint = "Enter username"
            setSingleLine(true)
            textSize = 16f
        }

        val padding = (24 * resources.displayMetrics.density).toInt()

        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)

            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Username")
            .setMessage("Choose your multiplayer username")
            .setView(container)
            .setPositiveButton("SAVE") { _, _ ->
                val newUsername = input.text.toString().trim()

                if (newUsername.isNotEmpty()) {
                    saveUsername(newUsername)

                    Toast.makeText(
                        this,
                        "Username saved",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Username cannot be empty",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun createSession() {
        val intent = Intent(
            this,
            MainActivity::class.java
        )

        intent.putExtra("SESSION_HOST", true)
        intent.putExtra("USERNAME", username)

        startActivity(intent)
    }

    private fun showJoinSessionDialog() {
        val input = EditText(this).apply {
            hint = "WT-XXXXXX"
            setSingleLine(true)
            textSize = 16f
            setPadding(24, 20, 24, 20)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Join session")
            .setMessage("Enter the session code:")
            .setView(input)
            .setPositiveButton("JOIN", null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            val joinButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            joinButton.setOnClickListener {
                val code = input.text.toString()
                    .trim()
                    .uppercase()

                if (!code.matches(Regex("WT-[A-Z0-9]{6}"))) {
                    input.error = "Enter a valid code, e.g. WT-82K4P6"
                    return@setOnClickListener
                }

                joinButton.isEnabled = false
                joinButton.text = "CHECKING..."

                checkSessionExists(
                    code = code,
                    onSuccess = {
                        if (isFinishing || isDestroyed) return@checkSessionExists

                        dialog.dismiss()

                        val intent = Intent(
                            this,
                            MainActivity::class.java
                        )

                        intent.putExtra("SESSION_HOST", false)
                        intent.putExtra("SESSION_CODE", code)
                        intent.putExtra("USERNAME", username)

                        startActivity(intent)
                    },
                    onFailure = { message ->
                        if (isFinishing || isDestroyed) return@checkSessionExists

                        joinButton.isEnabled = true
                        joinButton.text = "JOIN"

                        input.error = message

                        Toast.makeText(
                            this,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        dialog.show()
    }

    private fun checkSessionExists(
        code: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        fun checkWithAuthenticatedUser() {
            val uid = auth.currentUser?.uid

            if (uid.isNullOrBlank()) {
                onFailure("Could not authenticate user")
                return
            }

            database.child("rooms")
                .child(code)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.exists()) {
                        onFailure("Session does not exist")
                        return@addOnSuccessListener
                    }

                    val active = snapshot.child("active")
                        .getValue(Boolean::class.java) ?: false

                    val host = snapshot.child("host")
                        .getValue(String::class.java)

                    if (!active || host.isNullOrBlank()) {
                        onFailure("Session does not exist or is already closed")
                        return@addOnSuccessListener
                    }

                    if (snapshot.child("bans").hasChild(uid)) {
                        onFailure("You are banned from this session")
                        return@addOnSuccessListener
                    }

                    onSuccess()
                }
                .addOnFailureListener { error ->
                    onFailure(
                        "Could not verify session: ${error.message ?: "Firebase error"}"
                    )
                }
        }

        if (auth.currentUser != null) {
            checkWithAuthenticatedUser()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    checkWithAuthenticatedUser()
                }
                .addOnFailureListener { error ->
                    onFailure(
                        "Authentication failed: ${error.message ?: "Firebase error"}"
                    )
                }
        }
    }
}