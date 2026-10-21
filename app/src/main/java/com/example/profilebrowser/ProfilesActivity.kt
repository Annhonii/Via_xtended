package com.example.profilebrowser

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Home screen: list of profiles + "Create profile". */
class ProfilesActivity : AppCompatActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var adapter: ProfileAdapter
    private lateinit var emptyView: View

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            adapter.tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        findViewById<View>(R.id.root).applySystemBarInsets()

        repo = ProfileRepository(this)
        emptyView = findViewById(R.id.empty)

        adapter = ProfileAdapter(onOpen = ::openProfile, onMenu = ::showItemMenu)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@ProfilesActivity)
            adapter = this@ProfilesActivity.adapter
        }
        findViewById<MaterialButton>(R.id.createButton).setOnClickListener {
            showProfileDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        val list = repo.getAll()
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openProfile(p: UserProfile) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.webview_unsupported_title)
                .setMessage(R.string.webview_unsupported_message)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PROFILE_ID, p.id)
        )
    }

    private fun showItemMenu(p: UserProfile, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_edit -> showProfileDialog(p)
                    R.id.action_delete -> confirmDelete(p)
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(p: UserProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        ProfileStore.getInstance().deleteProfile(p.webProfileName)
                    }
                } catch (_: Exception) {
                    // Profile may be in use; ignore.
                }
                repo.delete(p.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Create (existing == null) or edit a profile. */
    private fun showProfileDialog(existing: UserProfile?) {
        val view = layoutInflater.inflate(R.layout.dialog_profile, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.nameLayout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.nameInput)
        val sw = view.findViewById<MaterialSwitch>(R.id.countdownSwitch)
        val group = view.findViewById<View>(R.id.countdownGroup)
        val timeBtn = view.findViewById<MaterialButton>(R.id.timeButton)
        val info = view.findViewById<TextView>(R.id.timeInfo)

        val cal = Calendar.getInstance()
        existing?.countdownEnd?.let { cal.timeInMillis = it }
        var hour = cal.get(Calendar.HOUR_OF_DAY)
        var minute = cal.get(Calendar.MINUTE)
        var timeChanged = false

        nameInput.setText(existing?.name)
        sw.isChecked = existing?.countdownEnd != null
        group.visibility = if (sw.isChecked) View.VISIBLE else View.GONE

        fun refreshTime() {
            timeBtn.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            val left = TimeUtil.nextOccurrence(hour, minute) - System.currentTimeMillis()
            info.text = getString(R.string.countdown_left, TimeUtil.format(left))
        }
        refreshTime()

        sw.setOnCheckedChangeListener { _, checked ->
            group.visibility = if (checked) View.VISIBLE else View.GONE
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    hour = h; minute = m; timeChanged = true
                    refreshTime()
                },
                hour, minute, DateFormat.is24HourFormat(this)
            ).show()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.create_profile else R.string.edit_profile)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    nameLayout.error = getString(R.string.name_required)
                    return@setOnClickListener
                }
                val end: Long? = when {
                    !sw.isChecked -> null
                    existing?.countdownEnd != null && !timeChanged -> existing.countdownEnd
                    else -> TimeUtil.nextOccurrence(hour, minute)
                }
                val profile = existing?.copy(name = name, countdownEnd = end)
                    ?: UserProfile(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        name = name,
                        countdownEnd = end,
                        lastUrl = ""
                    )
                repo.save(profile)
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
