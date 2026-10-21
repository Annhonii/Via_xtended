package com.example.profilebrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.Profile as WebProfile
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar

/**
 * Browser screen for ONE profile. The WebView is bound to an isolated WebView Profile
 * (androidx.webkit), so cookies / localStorage / cache / permissions are never shared
 * with any other profile. Everything is stored on disk, so data survives app restarts.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val HOME = "https://www.google.com"
    }

    private lateinit var repo: ProfileRepository
    private lateinit var profile: UserProfile
    private lateinit var webProfile: WebProfile
    private lateinit var webView: WebView

    private lateinit var outer: View
    private lateinit var content: View
    private lateinit var fullscreen: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var countdownView: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val downloadNames = mutableMapOf<Long, String>()
    private var pendingDownload: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            countdownView.showCountdown(profile.countdownEnd)
            handler.postDelayed(this, 1000)
        }
    }

    // ---- activity result launchers -------------------------------------------------------

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            pendingDownload = null
        }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val name = downloadNames[id] ?: return
            val dm = getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Snackbar.make(outer, getString(R.string.download_done, name), Snackbar.LENGTH_LONG)
                            .setAction(R.string.share) { shareDownloaded(id) }
                            .show()
                    } else {
                        Snackbar.make(outer, R.string.download_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        outer = findViewById(R.id.outer)
        content = findViewById(R.id.content)
        fullscreen = findViewById(R.id.fullscreen)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        countdownView = findViewById(R.id.countdown)
        content.applySystemBarInsets()

        repo = ProfileRepository(this)
        val p = repo.get(intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty())
        if (p == null || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            finish()
            return
        }
        profile = p
        findViewById<TextView>(R.id.profileName).text = profile.name

        // 1) Create the isolated profile and bind a fresh WebView to it BEFORE anything else.
        webProfile = ProfileStore.getInstance().getOrCreateProfile(profile.webProfileName)
        webView = WebView(this)
        WebViewCompat.setProfile(webView, profile.webProfileName)
        findViewById<FrameLayout>(R.id.webContainer).addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        setupWebView()
        setupToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        webView.loadUrl(profile.lastUrl.ifBlank { HOME })
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        webView.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            handler.removeCallbacks(ticker)
            saveLastUrl(webView.url)
            webProfile.cookieManager.flush()   // make sure logins are written to disk
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
            filePathCallback?.onReceiveValue(null)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---- setup ---------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // Look like normal Chrome (helps with sites that reject embedded WebViews).
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        val cm: CookieManager = webProfile.cookieManager
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                progress.visibility = View.INVISIBLE
                saveLastUrl(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }

            // File upload (<input type="file">) from this profile's pages.
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreen.addView(view)
                fullscreen.visibility = View.VISIBLE
                content.visibility = View.GONE
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = hideCustomView()
        }

        // Download from this profile (uses THIS profile's cookies).
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener { showMenu(it) }

        urlBar.setOnEditorActionListener { v, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                webView.loadUrl(normalizeUrl(v.text.toString()))
                v.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
    }

    // ---- menu ----------------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.browser_menu, menu)
            menu.findItem(R.id.action_back).isEnabled = webView.canGoBack()
            menu.findItem(R.id.action_forward).isEnabled = webView.canGoForward()
            menu.findItem(R.id.action_remove_countdown).isVisible = profile.countdownEnd != null
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_back -> webView.goBack()
                    R.id.action_forward -> webView.goForward()
                    R.id.action_reload -> webView.reload()
                    R.id.action_share_page -> sharePage()
                    R.id.action_countdown -> pickCountdown()
                    R.id.action_remove_countdown -> updateProfile(profile.copy(countdownEnd = null))
                    R.id.action_clear_data -> confirmClearData()
                }
                true
            }
            show()
        }
    }

    private fun pickCountdown() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, h, m ->
                updateProfile(profile.copy(countdownEnd = TimeUtil.nextOccurrence(h, m)))
                Toast.makeText(this, R.string.countdown_set, Toast.LENGTH_SHORT).show()
            },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun updateProfile(updated: UserProfile) {
        profile = updated
        repo.save(updated)
        countdownView.showCountdown(updated.countdownEnd)
    }

    private fun saveLastUrl(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank" || url == profile.lastUrl) return
        profile = profile.copy(lastUrl = url)
        repo.save(profile)
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                webProfile.cookieManager.removeAllCookies(null)
                webProfile.webStorage.deleteAllData()
                webView.clearCache(true)
                webView.reload()
                Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- share & download ----------------------------------------------------------------

    private fun sharePage() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, webView.url ?: return)
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    private fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload(url, userAgent, disposition, mime) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val fileName = URLUtil.guessFileName(url, disposition, mime)
        val folder = profile.name.replace(Regex("[^A-Za-z0-9_-]"), "_")

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            mime?.let { setMimeType(it) }
            webProfile.cookieManager.getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$folder/$fileName")
        }
        try {
            val id = getSystemService(DownloadManager::class.java).enqueue(request)
            downloadNames[id] = fileName
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDownloaded(id: Long) {
        val dm = getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(dm.getMimeTypeForDownloadedFile(id) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        content.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun normalizeUrl(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> HOME
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }
}
