package com.example.warthundermapdraw

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
        private const val KEY_ACCEPTED_DOCUMENTS_VERSION = "accepted_documents_version"
        private const val DOCUMENTS_VERSION = 1
        private const val DEFAULT_USERNAME = "User"
        private const val MAX_USERNAME_LENGTH = 20
        private const val MAX_PARTICIPANTS = 8
        private const val DATABASE_URL =
            "https://wt-tactical-map-5f0fb-default-rtdb.europe-west1.firebasedatabase.app/"
    }

    private lateinit var usernameButton: TextView
    private lateinit var createSessionButton: Button
    private lateinit var joinSessionButton: Button
    private lateinit var termsButton: TextView
    private lateinit var privacyButton: TextView

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance(DATABASE_URL).reference
    private var username = DEFAULT_USERNAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        applySystemInsets()
        initializeViews()
        loadUsername()
        updateUsernameButton()
        initializeButtons()
        checkDocumentsAcceptance()
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.HomeRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun initializeViews() {
        usernameButton = findViewById(R.id.UsernameButton)
        createSessionButton = findViewById(R.id.CreateSessionButton)
        joinSessionButton = findViewById(R.id.JoinSessionButton)
        termsButton = findViewById(R.id.TermsButton)
        privacyButton = findViewById(R.id.PrivacyButton)
    }

    private fun initializeButtons() {
        usernameButton.setOnClickListener { showUsernameDialog() }
        createSessionButton.setOnClickListener { createSession() }
        joinSessionButton.setOnClickListener { showJoinSessionDialog() }
        termsButton.setOnClickListener { showDocument("TERMS.md", "Terms of Use") }
        privacyButton.setOnClickListener { showDocument("PRIVACY.md", "Privacy Policy") }

        termsButton.paintFlags = termsButton.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        privacyButton.paintFlags = privacyButton.paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }

    private fun checkDocumentsAcceptance() {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val acceptedVersion = preferences.getInt(KEY_ACCEPTED_DOCUMENTS_VERSION, 0)

        if (acceptedVersion != DOCUMENTS_VERSION) {
            showDocumentsAcceptanceDialog()
        }
    }

    private fun showDocumentsAcceptanceDialog() {
        val termsCheckBox = CheckBox(this).apply {
            text = "I have read and accept the Terms of Use"
            setTextColor(android.graphics.Color.rgb(220, 232, 245))
            textSize = 14f
        }

        val privacyCheckBox = CheckBox(this).apply {
            text = "I have read and accept the Privacy Policy"
            setTextColor(android.graphics.Color.rgb(220, 232, 245))
            textSize = 14f
        }

        val termsLink = TextView(this).apply {
            text = "READ TERMS OF USE"
            setTextColor(android.graphics.Color.rgb(114, 183, 245))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 12, 0, 12)
            setOnClickListener { showDocument("TERMS.md", "Terms of Use") }
        }

        val privacyLink = TextView(this).apply {
            text = "READ PRIVACY POLICY"
            setTextColor(android.graphics.Color.rgb(114, 183, 245))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 12, 0, 12)
            setOnClickListener { showDocument("PRIVACY.md", "Privacy Policy") }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(termsLink)
            addView(termsCheckBox)
            addView(privacyLink)
            addView(privacyCheckBox)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Terms and Privacy")
            .setMessage(
                "Before using WTDrawMap, please read and accept both documents."
            )
            .setView(content)
            .setPositiveButton("ACCEPT", null)
            .setNegativeButton("DECLINE") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val acceptButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            acceptButton.isEnabled = false

            val updateAcceptButton = {
                acceptButton.isEnabled =
                    termsCheckBox.isChecked && privacyCheckBox.isChecked
            }

            termsCheckBox.setOnCheckedChangeListener { _, _ ->
                updateAcceptButton()
            }

            privacyCheckBox.setOnCheckedChangeListener { _, _ ->
                updateAcceptButton()
            }

            acceptButton.setOnClickListener {
                if (termsCheckBox.isChecked && privacyCheckBox.isChecked) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_ACCEPTED_DOCUMENTS_VERSION, DOCUMENTS_VERSION)
                        .apply()

                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun showDocument(fileName: String, title: String) {
        val document = try {
            assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not load $title",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val textView = TextView(this).apply {
            text = document
            setTextColor(android.graphics.Color.rgb(220, 232, 245))
            textSize = 14f
            setLineSpacing(4f, 1f)
            setPadding(20, 16, 20, 16)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun loadUsername() {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        username = preferences.getString(KEY_USERNAME, null)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_USERNAME_LENGTH)
            ?: generateRandomUsername().also {
                preferences.edit().putString(KEY_USERNAME, it).apply()
            }

        preferences.edit().putString(KEY_USERNAME, username).apply()
    }

    private fun generateRandomUsername(): String =
        "$DEFAULT_USERNAME${Random.nextInt(1000, 10000)}"

    private fun saveUsername(newUsername: String) {
        username = newUsername.trim().take(MAX_USERNAME_LENGTH)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
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
            filters = arrayOf(InputFilter.LengthFilter(MAX_USERNAME_LENGTH))
        }

        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Username")
            .setMessage("Choose your multiplayer username (maximum $MAX_USERNAME_LENGTH characters)")
            .setView(container)
            .setPositiveButton("SAVE") { _, _ ->
                val newUsername = input.text.toString().trim()

                if (newUsername.isNotEmpty()) {
                    if (newUsername.length > MAX_USERNAME_LENGTH) {
                        Toast.makeText(
                            this,
                            "Username cannot exceed $MAX_USERNAME_LENGTH characters",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        saveUsername(newUsername)
                        Toast.makeText(this, "Username saved", Toast.LENGTH_SHORT).show()
                    }
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
        AlertDialog.Builder(this)
            .setTitle("Network connection")
            .setMessage(
                "Make sure your phone is connected to the same Wi-Fi network as the device running War Thunder.\n\n" +
                        "The phone and the game must be on the same local network to receive the tactical map."
            )
            .setPositiveButton("CONTINUE") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("SESSION_HOST", true)
                    putExtra("USERNAME", username)
                })
            }
            .setNegativeButton("CANCEL", null)
            .show()
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
                val code = input.text.toString().trim().uppercase()

                if (!code.matches(Regex("WT-[A-Z0-9]{6}"))) {
                    input.error = "Enter a valid code, e.g. WT-82K4P6"
                    return@setOnClickListener
                }

                joinButton.isEnabled = false
                joinButton.text = "CHECKING..."

                checkSessionExists(
                    code,
                    onSuccess = {
                        if (isFinishing || isDestroyed) return@checkSessionExists

                        dialog.dismiss()
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra("SESSION_HOST", false)
                            putExtra("SESSION_CODE", code)
                            putExtra("USERNAME", username)
                        })
                    },
                    onFailure = { message ->
                        if (isFinishing || isDestroyed) return@checkSessionExists

                        joinButton.isEnabled = true
                        joinButton.text = "JOIN"
                        input.error = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

            val room = database.child("rooms").child(code)

            room.child("host").get()
                .addOnSuccessListener { hostSnapshot ->
                    val hostUid = hostSnapshot.getValue(String::class.java)

                    if (hostUid.isNullOrBlank()) {
                        onFailure("Session does not exist")
                        return@addOnSuccessListener
                    }

                    room.child("active").get()
                        .addOnSuccessListener { activeSnapshot ->
                            val active = activeSnapshot.getValue(Boolean::class.java) == true

                            if (!active) {
                                onFailure("Session is closed")
                                return@addOnSuccessListener
                            }

                            room.child("bans").child(uid).get()
                                .addOnSuccessListener { banSnapshot ->
                                    if (banSnapshot.exists()) {
                                        onFailure("You are banned from this session")
                                        return@addOnSuccessListener
                                    }

                                    room.child("members").get()
                                        .addOnSuccessListener { membersSnapshot ->
                                            val memberCount =
                                                membersSnapshot.childrenCount.toInt()
                                            val alreadyMember = membersSnapshot.hasChild(uid)

                                            if (memberCount >= MAX_PARTICIPANTS && !alreadyMember) {
                                                onFailure("Session is full (8/8 players)")
                                                return@addOnSuccessListener
                                            }

                                            if (!membersSnapshot.hasChild(hostUid)) {
                                                onFailure("Invalid session: host is not in the session")
                                                return@addOnSuccessListener
                                            }

                                            onSuccess()
                                        }
                                        .addOnFailureListener { error ->
                                            onFailure(
                                                "Could not check session participants: ${error.message ?: "Firebase error"}"
                                            )
                                        }
                                }
                                .addOnFailureListener { error ->
                                    onFailure(
                                        "Could not check session access: ${error.message ?: "Firebase error"}"
                                    )
                                }
                        }
                        .addOnFailureListener { error ->
                            onFailure(
                                "Could not check session status: ${error.message ?: "Firebase error"}"
                            )
                        }
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