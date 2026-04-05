# GitHub-Based In-App Update System — Implementation Guide

A complete guide to implementing a self-contained in-app update system for Android apps that distribute APKs via GitHub Releases. Zero Google Play dependency. Includes version checking, a changelog dialog with rendered Markdown, a download progress bar, and APK installation.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Prerequisites & Dependencies](#2-prerequisites--dependencies)
3. [Step 1 — Android Manifest Setup](#3-step-1--android-manifest-setup)
4. [Step 2 — FileProvider Configuration](#4-step-2--fileprovider-configuration)
5. [Step 3 — UpdateChecker (Version Check Singleton)](#5-step-3--updatechecker-version-check-singleton)
6. [Step 4 — AppUpdater (Download & Install State Machine)](#6-step-4--appupdater-download--install-state-machine)
7. [Step 5 — Update Card Layout with Progress Bar](#7-step-5--update-card-layout-with-progress-bar)
8. [Step 6 — Changelog Dialog Layout](#8-step-6--changelog-dialog-layout)
9. [Step 7 — Settings Fragment (Wiring It All Together)](#9-step-7--settings-fragment-wiring-it-all-together)
10. [Step 8 — Badge on Navigation (Optional)](#10-step-8--badge-on-navigation-optional)
11. [Step 9 — Triggering the Check on App Start](#11-step-9--triggering-the-check-on-app-start)
12. [String Resources](#12-string-resources)
13. [GitHub Releases Setup](#13-github-releases-setup)
14. [How the GitHub API Response Maps to the Code](#14-how-the-github-api-response-maps-to-the-code)
15. [Full State Machine Reference](#15-full-state-machine-reference)
16. [Customization Points](#16-customization-points)
17. [Troubleshooting](#17-troubleshooting)

---

## 1. Architecture Overview

The system has three components:

```txt
┌──────────────────────┐     ┌───────────────────┐     ┌──────────────────────┐
│    UpdateChecker     │     │    AppUpdater     │     │  SettingsFragment    │
│    (singleton)       │────>│  (per-fragment)   │────>│  (UI layer)          │
│                      │     │                   │     │                      │
│ • GitHub API check   │     │ • DownloadManager │     │ • Update card + bar  │
│ • Version comparison │     │ • Progress poll   │     │ • Changelog dialog   │
│ • Stores changelog,  │     │ • APK install     │     │ • Markdown rendering │
│   size, URL, version │     │ • State machine   │     │ • Card clickability  │
└──────────────────────┘     └───────────────────┘     └──────────────────────┘
```

**No external services required.** The only network call is a single GET to `api.github.com`. The download uses Android's built-in `DownloadManager`. The install uses the system package installer via `Intent.ACTION_VIEW`.

### Data flow

1. `UpdateChecker.check(context)` → hits GitHub Releases API → stores version, URL, changelog, APK size
2. User taps update card → `AppUpdater.onUpdateTapped()` returns `true` → fragment shows changelog dialog
3. User taps "Update" in dialog → `AppUpdater.startDownload()` → `DownloadManager` enqueues request
4. 500ms polling loop reads bytes downloaded → fires progress callback → UI updates bar + percentage
5. Download complete → state transitions to `INSTALLING` → `installApk()` via `FileProvider` + `ACTION_VIEW`

---

## 2. Prerequisites & Dependencies

### Minimum requirements

- **minSdk 21+** (the implementation below uses `Build.VERSION_CODES.O` checks for install permissions, which is API 26)
- **Material 3** (`com.google.android.material:material:1.13.0` or later) for `MaterialAlertDialogBuilder`, `LinearProgressIndicator`, `MaterialCardView`
- **ViewBinding** enabled in your module

### Gradle dependencies

In your version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
markwon = "4.6.2"

[libraries]
markwon = { module = "io.noties.markwon:core", version.ref = "markwon" }
```

In your app-level `build.gradle.kts`:

```kotlin
dependencies {
    // ... your existing deps ...
    implementation(libs.markwon)  // Markdown rendering for changelogs
}
```

If you don't use a version catalog, use this directly:

```kotlin
implementation("io.noties.markwon:core:4.6.2")
```

### Build features

```kotlin
android {
    buildFeatures {
        viewBinding = true
    }
}
```

---

## 3. Step 1 — Android Manifest Setup

Two things are required in your `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required: download the APK from GitHub -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Required: trigger the system package installer -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <application ...>
        <!-- Your activities here -->

        <!-- Required: serve the downloaded APK to the system installer -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

### Why each permission

| Permission                 | Why                                                                                                                               |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `INTERNET`                 | Needed for both the GitHub API call and the APK download                                                                          |
| `REQUEST_INSTALL_PACKAGES` | Needed on API 26+ to launch the system package installer for sideloaded APKs. Without this, the install intent will silently fail |

---

## 4. Step 2 — FileProvider Configuration

Create `res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="downloads" path="Download/" />
    <external-files-path name="downloads_alt" path="Downloads/" />
</paths>
```

This tells `FileProvider` which directories it's allowed to serve files from. The APK is saved to `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`, which maps to `external-files-path` with `path="Download/"`. The `downloads_alt` entry covers the case where the system returns `Downloads/` instead of `Download/`.

### How `FileProvider` is used

When the download completes, the code calls:

```kotlin
val apkUri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    file  // File object pointing to the downloaded APK
)
```

This generates a `content://` URI that the system installer can read, instead of a raw `file://` URI (which is blocked on API 24+).

---

## 5. Step 3 — UpdateChecker (Version Check Singleton)

This is a Kotlin `object` (singleton) that handles the GitHub API call and version comparison. It's designed to be called from `onCreate()` of your main Activity — fire-and-forget.

### Full implementation

```kotlin
package com.yourapp.updater

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    // ── Public state (read-only from outside) ───────────────────────
    var latestVersion: String? = null
        private set
    var latestApkUrl: String? = null
        private set
    var releaseBody: String? = null      // Changelog (Markdown)
        private set
    var apkSizeBytes: Long = 0L          // APK file size in bytes
        private set
    var updateAvailable: Boolean = false
        private set

    // ── Listeners ───────────────────────────────────────────────────
    private var listeners = mutableListOf<() -> Unit>()
    private var checking = false

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    // ── Main check ──────────────────────────────────────────────────
    fun check(context: Context) {
        if (checking || updateAvailable) return   // don't re-check
        checking = true

        val currentVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0"
        } catch (_: Exception) { "0" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                //  ╔══════════════════════════════════════════════════╗
                //  ║  CHANGE THIS URL to your own GitHub repo        ║
                //  ╚══════════════════════════════════════════════════╝
                val url = URL("https://api.github.com/repos/YOUR_USER/YOUR_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tagVersion = json.getString("tag_name").removePrefix("v")

                    if (isNewerVersion(currentVersion, tagVersion)) {
                        val assets = json.getJSONArray("assets")
                        var apkUrl: String? = null
                        var apkSize = 0L
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                        if (apkUrl != null) {
                            latestApkUrl = apkUrl
                            latestVersion = tagVersion
                            releaseBody = json.optString("body", "")
                            apkSizeBytes = apkSize
                            updateAvailable = true
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }

            checking = false
            withContext(Dispatchers.Main) { listeners.forEach { it() } }
        }
    }

    fun clear() {
        updateAvailable = false
        latestVersion = null
        latestApkUrl = null
        releaseBody = null
        apkSizeBytes = 0L
    }

    // ── Semver comparison ───────────────────────────────────────────
    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val cur = current.split("-")[0].split(".")
            val lat = latest.split("-")[0].split(".")
            for (i in 0 until maxOf(cur.size, lat.size)) {
                val c = cur.getOrNull(i)?.toIntOrNull() ?: 0
                val l = lat.getOrNull(i)?.toIntOrNull() ?: 0
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) { }
        return false
    }
}
```

### What to customize

| Item         | What to change                                                                                                                                                    |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GitHub URL   | Replace `YOUR_USER/YOUR_REPO` with your GitHub repo path                                                                                                          |
| Asset filter | The code finds the first `.apk` asset. If your release has multiple APKs (e.g. per ABI), adjust the `endsWith(".apk")` check to match a specific filename pattern |
| Tag format   | The code strips a `v` prefix (e.g. `v1.2.3` → `1.2.3`). If your tags use a different format, adjust `removePrefix()`                                              |

### How `isNewerVersion` works

Splits both version strings on `.`, compares each segment as an integer left-to-right. Strips `-suffix` first (so `1.2.3-beta` becomes `1.2.3`).

```txt
current = "1.2.3"    latest = "1.3.0"
segment 0: 1 == 1    → continue
segment 1: 2 < 3     → return true (newer)
```

### Guard behavior

- If a check is already in-flight (`checking == true`), `check()` returns immediately
- If an update was already found (`updateAvailable == true`), `check()` returns immediately — no redundant API calls
- All listeners are called on the **main thread** via `withContext(Dispatchers.Main)`, so UI updates are safe

---

## 6. Step 4 — AppUpdater (Download & Install State Machine)

This class handles the download via `DownloadManager`, tracks progress by polling every 500ms, and installs the APK via `FileProvider` + `ACTION_VIEW`. It's created per-Fragment and tied to the fragment's lifecycle.

### Full implementation

```kotlin
package com.yourapp.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.yourapp.R
import java.io.File

class AppUpdater(private val fragment: Fragment) {

    // ── States ──────────────────────────────────────────────────────
    enum class State { IDLE, UPDATE_AVAILABLE, DOWNLOADING, DOWNLOADED, INSTALLING }

    var state: State = State.IDLE
        private set

    // ── Callbacks ───────────────────────────────────────────────────
    /** Called when state or subtitle text changes */
    var onStateChanged: ((State, String) -> Unit)? = null
    /** Progress: 0–100 for determinate, -1 for indeterminate */
    var onDownloadProgress: ((Int) -> Unit)? = null

    // ── Internal state ──────────────────────────────────────────────
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var pendingInstallFileName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downloadCheckRunnable: Runnable? = null

    // Registered in constructor — MUST be called before fragment STARTED
    private val installPermissionLauncher: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val context = fragment.context ?: return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.packageManager.canRequestPackageInstalls()) {
                    pendingInstallFileName?.let { installApk(context, it) }
                    pendingInstallFileName = null
                } else {
                    Toast.makeText(
                        context,
                        R.string.install_permission_denied,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Sync state with [UpdateChecker]. Call from onViewCreated().
     */
    fun syncFromChecker() {
        if (UpdateChecker.updateAvailable) {
            state = State.UPDATE_AVAILABLE
            notify(fragment.requireContext().getString(
                R.string.update_available_format,
                UpdateChecker.latestVersion
            ))
        }
    }

    /**
     * Handle a tap on the update card.
     * Returns true if an update is available (caller should show dialog).
     */
    fun onUpdateTapped(context: Context): Boolean {
        when (state) {
            State.UPDATE_AVAILABLE -> return true
            State.DOWNLOADING, State.INSTALLING -> { /* ignore taps */ }
            else -> {
                notify(context.getString(R.string.checking_for_updates_status))
                UpdateChecker.addListener(checkerListener)
                UpdateChecker.check(context)
            }
        }
        return false
    }

    /**
     * Start the download. Call AFTER the user confirms in the dialog.
     */
    fun startDownload(context: Context) {
        val url = UpdateChecker.latestApkUrl
        val version = UpdateChecker.latestVersion
        if (url != null && version != null) {
            state = State.DOWNLOADING
            onDownloadProgress?.invoke(-1)  // indeterminate while initializing
            notify(context.getString(R.string.download_initializing))
            downloadAndInstallApk(context, url, version)
        } else {
            notify(context.getString(R.string.update_info_missing))
        }
    }

    /**
     * Clean up when the fragment's view is destroyed.
     */
    fun cleanup() {
        UpdateChecker.removeListener(checkerListener)
        downloadCheckRunnable?.let { handler.removeCallbacks(it) }
        unregisterReceiver(fragment.requireContext())
    }

    // ── Checker listener ────────────────────────────────────────────

    private val checkerListener: () -> Unit = {
        UpdateChecker.removeListener(checkerListener)
        if (UpdateChecker.updateAvailable) {
            state = State.UPDATE_AVAILABLE
            notify(fragment.requireContext().getString(
                R.string.update_available_format,
                UpdateChecker.latestVersion
            ))
        } else {
            state = State.IDLE
            notify(fragment.requireContext().getString(R.string.up_to_date))
        }
    }

    // ── Receiver management ─────────────────────────────────────────

    fun unregisterReceiver(context: Context) {
        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        downloadReceiver = null
    }

    private fun cleanupDownload(context: Context) {
        downloadCheckRunnable?.let { handler.removeCallbacks(it) }
        downloadCheckRunnable = null
        if (downloadId != -1L) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE)
                    as DownloadManager
                dm.remove(downloadId)
            } catch (_: Exception) {}
            downloadId = -1
        }
        unregisterReceiver(context)
    }

    // ── Download ────────────────────────────────────────────────────

    private fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        version: String
    ) {
        try {
            cleanupDownload(context)

            //  ╔══════════════════════════════════════════════════════╗
            //  ║  CHANGE THIS filename pattern to match your app     ║
            //  ╚══════════════════════════════════════════════════════╝
            val fileName = "YourApp-$version.apk"

            // Register broadcast receiver for download completion
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1
                    )
                    if (id == downloadId) {
                        downloadCheckRunnable?.let { handler.removeCallbacks(it) }
                        handleDownloadComplete(context, fileName)
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // Enqueue the download
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle(context.getString(R.string.ptdl_update_title))
                .setDescription(
                    context.getString(R.string.downloading_version_format, version)
                )
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, fileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE)
                as DownloadManager
            downloadId = dm.enqueue(request)

            // Start polling for progress
            startDownloadPolling(context, dm, fileName)
        } catch (e: Exception) {
            state = State.IDLE
            notify(context.getString(R.string.download_setup_failed_format, e.message))
        }
    }

    private fun startDownloadPolling(
        context: Context,
        dm: DownloadManager,
        fileName: String
    ) {
        downloadCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val cursor: Cursor? = dm.query(
                        DownloadManager.Query().setFilterById(downloadId)
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS
                        )
                        when (cursor.getInt(statusIndex)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                cursor.close()
                                downloadCheckRunnable?.let {
                                    handler.removeCallbacks(it)
                                }
                                handleDownloadComplete(context, fileName)
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reasonIdx = cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_REASON
                                )
                                val reason = cursor.getInt(reasonIdx)
                                cursor.close()
                                downloadCheckRunnable?.let {
                                    handler.removeCallbacks(it)
                                }
                                state = State.IDLE
                                onDownloadProgress?.invoke(-1)
                                notify(context.getString(
                                    R.string.download_failed_format, reason
                                ))
                                return
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                val bytesIdx = cursor.getColumnIndex(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                )
                                val totalIdx = cursor.getColumnIndex(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                )
                                if (bytesIdx >= 0 && totalIdx >= 0) {
                                    val downloaded = cursor.getLong(bytesIdx)
                                    val total = cursor.getLong(totalIdx)
                                    if (total > 0) {
                                        val pct = ((downloaded * 100) / total)
                                            .toInt().coerceIn(0, 100)
                                        handler.post {
                                            onDownloadProgress?.invoke(pct)
                                            notify(context.getString(
                                                R.string.download_progress_format,
                                                pct
                                            ))
                                        }
                                    }
                                }
                            }
                        }
                        cursor.close()
                    }
                    handler.postDelayed(this, 500)
                } catch (e: Exception) {
                    downloadCheckRunnable?.let { handler.removeCallbacks(it) }
                    state = State.IDLE
                    notify(context.getString(
                        R.string.download_polling_error_format, e.message
                    ))
                }
            }
        }
        handler.post(downloadCheckRunnable!!)
    }

    private fun handleDownloadComplete(context: Context, fileName: String) {
        unregisterReceiver(context)
        state = State.INSTALLING
        onDownloadProgress?.invoke(-1)   // indeterminate during install
        notify(context.getString(R.string.installing_update_status))
        installApk(context, fileName)
    }

    // ── Install ─────────────────────────────────────────────────────

    private fun installApk(context: Context, fileName: String) {
        try {
            // API 26+: check for "install unknown apps" permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    pendingInstallFileName = fileName
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = Uri.parse("package:${context.packageName}")
                    installPermissionLauncher.launch(intent)
                    return
                }
            }

            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (!file.exists()) return

            val intent = Intent(Intent.ACTION_VIEW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                intent.setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.setDataAndType(
                    Uri.fromFile(file),
                    "application/vnd.android.package-archive"
                )
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fragment.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.installation_failed_format, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun notify(message: String) {
        onStateChanged?.invoke(state, message)
    }
}
```

### Key design decisions

| Decision                                              | Rationale                                                                                                                      |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `DownloadManager` instead of `OkHttp`/manual stream   | Zero extra dependencies. System handles retries, notifications, and network-change resilience. Survives process death.         |
| 500ms polling loop                                    | `DownloadManager` has no streaming progress callback. Polling is the standard approach. 500ms balances responsiveness vs. CPU. |
| `BroadcastReceiver` + polling as dual mechanism       | The receiver catches completion instantly; polling is the backup if the receiver misses the broadcast (e.g. race condition).   |
| `installPermissionLauncher` registered in constructor | `registerForActivityResult()` must be called before the fragment reaches `STARTED`. The constructor is the safest place.       |
| `startDownload()` is separate from `onUpdateTapped()` | Gives the UI layer control — the fragment shows a dialog first, and only calls `startDownload()` after the user confirms.      |

---

## 7. Step 5 — Update Card Layout with Progress Bar

Add this inside your settings/preferences layout. This is a `MaterialCardView` containing a title, subtitle, and a `LinearProgressIndicator` that starts hidden.

```xml
<!-- Inside your settings layout's LinearLayout/ScrollView -->

<com.google.android.material.card.MaterialCardView
    android:id="@+id/update_card"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    style="?attr/materialCardViewOutlinedStyle">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/update_title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/check_for_updates"
            android:textAppearance="?attr/textAppearanceTitleSmall" />

        <TextView
            android:id="@+id/update_subtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="@string/tap_to_check"
            android:textAppearance="?attr/textAppearanceBodySmall"
            android:textColor="?attr/colorOnSurfaceVariant" />

        <com.google.android.material.progressindicator.LinearProgressIndicator
            android:id="@+id/update_progress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:indeterminate="true"
            android:visibility="gone"
            app:trackCornerRadius="2dp" />

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```

### Progress bar behavior

| Phase                   | `isIndeterminate` | `visibility` | Appearance                       |
| ----------------------- | ----------------- | ------------ | -------------------------------- |
| Idle / Update available | —                 | `GONE`       | Hidden                           |
| Download initializing   | `true`            | `VISIBLE`    | Animated pulsing/sliding bar     |
| Downloading (0–100%)    | `false`           | `VISIBLE`    | Filled bar reflecting percentage |
| Installing              | `true`            | `VISIBLE`    | Animated pulsing/sliding bar     |
| Error / back to idle    | —                 | `GONE`       | Hidden                           |

---

## 8. Step 6 — Changelog Dialog Layout

Create `res/layout/dialog_update.xml`. This is the **custom view** inflated inside a `MaterialAlertDialogBuilder`. The title and buttons are handled by the dialog builder itself — this layout only contains the body content.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingHorizontal="24dp">

    <!-- Download size (e.g. "Download size: 12.4 MB") -->
    <TextView
        android:id="@+id/update_size"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="?attr/textAppearanceBodyMedium"
        android:textColor="?attr/colorOnSurfaceVariant" />

    <com.google.android.material.divider.MaterialDivider
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:layout_marginBottom="12dp" />

    <!-- Scrollable changelog (rendered Markdown) -->
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:maxHeight="300dp"
        android:fadeScrollbars="false">

        <TextView
            android:id="@+id/update_changelog"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textAppearance="?attr/textAppearanceBodySmall"
            android:lineSpacingMultiplier="1.3" />

    </ScrollView>

</LinearLayout>
```

### Why a `ScrollView` with `maxHeight`

GitHub release notes can be arbitrarily long. The `ScrollView` with `maxHeight="300dp"` prevents the dialog from growing taller than the screen while still allowing the user to scroll through lengthy changelogs.

---

## 9. Step 7 — Settings Fragment (Wiring It All Together)

This is where all three pieces connect. The fragment:

1. Creates `AppUpdater` in `onCreate()` (so `registerForActivityResult` is called early enough)
2. Wires up the two callbacks in `onViewCreated()`
3. Shows the changelog dialog when the card is tapped and an update is available
4. Calls `appUpdater.startDownload()` when the user confirms

### Key wiring code

```kotlin
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appUpdater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MUST be created before STARTED state
        appUpdater = AppUpdater(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Callback 1: State changes → update subtitle + card clickability ──
        appUpdater.onStateChanged = fun(state: AppUpdater.State, message: String) {
            if (_binding == null) return
            binding.updateSubtitle.text = message
            when (state) {
                AppUpdater.State.DOWNLOADING,
                AppUpdater.State.INSTALLING -> {
                    binding.updateProgress.isVisible = true
                    binding.updateCard.isClickable = false
                    binding.updateCard.isFocusable = false
                }
                else -> {
                    binding.updateProgress.isVisible = false
                    binding.updateCard.isClickable = true
                    binding.updateCard.isFocusable = true
                }
            }
        }

        // ── Callback 2: Download progress → update progress bar mode ──
        appUpdater.onDownloadProgress = fun(progress: Int) {
            if (_binding == null) return
            if (progress < 0) {
                binding.updateProgress.isIndeterminate = true
            } else {
                binding.updateProgress.isIndeterminate = false
                binding.updateProgress.setProgressCompat(progress, true)
            }
        }

        // ── Initial sync ──
        appUpdater.syncFromChecker()

        // ── Card click handler ──
        binding.updateCard.setOnClickListener {
            if (appUpdater.onUpdateTapped(requireContext())) {
                showUpdateDialog()
            }
        }
    }

    private fun showUpdateDialog() {
        val ctx = context ?: return
        val version = UpdateChecker.latestVersion ?: return
        val body = UpdateChecker.releaseBody
        val sizeBytes = UpdateChecker.apkSizeBytes

        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

        // Download size
        dialogView.findViewById<TextView>(R.id.update_size).text =
            getString(R.string.update_dialog_size_format,
                Formatter.formatFileSize(ctx, sizeBytes))

        // Changelog (rendered Markdown)
        val changelogView = dialogView.findViewById<TextView>(R.id.update_changelog)
        if (!body.isNullOrBlank()) {
            val markwon = Markwon.create(ctx)
            markwon.setMarkdown(changelogView, body)
        } else {
            changelogView.text = getString(R.string.no_changelog)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.update_dialog_title_format, version))
            .setView(dialogView)
            .setPositiveButton(R.string.update_button) { dialog, _ ->
                dialog.dismiss()
                appUpdater.startDownload(ctx)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    override fun onDestroyView() {
        appUpdater.cleanup()
        super.onDestroyView()
        _binding = null
    }
}
```

### Required imports

```kotlin
import android.text.format.Formatter
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
```

### Why `fun()` instead of lambda `{}`

The callbacks use `fun(params) { ... }` (anonymous function) syntax instead of `{ params -> ... }` (lambda) because the anonymous function form supports `return` to exit early. With lambdas, you'd need `return@labelName` but there's no label available for property-assigned lambdas — this causes an "Unresolved label" compile error.

```kotlin
// ✗ WON'T COMPILE — no label for property assignment
appUpdater.onStateChanged = { state, message ->
    if (_binding == null) return@onStateChanged  // Error: Unresolved label
}

// ✓ COMPILES — anonymous function supports bare return
appUpdater.onStateChanged = fun(state: AppUpdater.State, message: String) {
    if (_binding == null) return  // Works fine
}
```

---

## 10. Step 8 — Badge on Navigation (Optional)

If your app has a `BottomNavigationView`, you can show a red dot on the Settings tab when an update is available. Add this in your `MainActivity.onCreate()`:

```kotlin
val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

val updateBadge: () -> Unit = {
    if (UpdateChecker.updateAvailable) {
        val badge = bottomNav.getOrCreateBadge(R.id.navigation_settings)
        badge.isVisible = true
        badge.clearNumber()   // dot only, no number
    } else {
        bottomNav.removeBadge(R.id.navigation_settings)
    }
}
UpdateChecker.addListener(updateBadge)
updateBadge()  // check immediately for pre-existing state
```

`clearNumber()` makes it a small colored dot instead of a numbered badge. The dot uses the Material theme's `colorError` by default (usually red).

---

## 11. Step 9 — Triggering the Check on App Start

In your main Activity's `onCreate()`, add one line:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... your setup code ...

    UpdateChecker.check(this)
}
```

This fires a single background coroutine. The API call has a 10-second timeout. If it fails (no network, rate-limited, etc.), it silently catches the exception and notifies listeners with `updateAvailable = false`. The user sees nothing.

### GitHub API rate limits

- **Unauthenticated:** 60 requests/hour per IP
- For most apps this is fine (one check per app launch)
- If you need more, add an `Authorization: token YOUR_TOKEN` header — but be careful not to ship tokens in the APK. Consider a server proxy instead.

---

## 12. String Resources

Add these to your `res/values/strings.xml`:

```xml
<!-- Update system -->
<string name="check_for_updates">Check for updates</string>
<string name="tap_to_check">Tap to check for new versions</string>
<string name="install_permission_denied">Install permission denied</string>
<string name="update_available_format">Update available: %s — tap to install</string>
<string name="downloading_update">Downloading update…</string>
<string name="update_info_missing">Update info missing, cannot download.</string>
<string name="checking_for_updates_status">Checking for updates…</string>
<string name="up_to_date">You\'re on the latest version</string>
<string name="ptdl_update_title">App Update</string>
<string name="downloading_version_format">Downloading version %s</string>
<string name="download_setup_failed_format">Download setup failed: %s</string>
<string name="download_failed_format">Download failed (reason: %d)</string>
<string name="download_polling_error_format">Download polling error: %s</string>
<string name="download_complete_installing">Download complete, installing…</string>
<string name="installing_update">Installing update…</string>
<string name="installation_failed_format">Installation failed: %s</string>
<string name="update_dialog_title_format">Update to %s</string>
<string name="update_dialog_size_format">Download size: %s</string>
<string name="download_initializing">Initializing download…</string>
<string name="download_progress_format">Downloading… %d%%</string>
<string name="installing_update_status">Installing update…</string>
<string name="update_button">Update</string>
<string name="cancel_button">Cancel</string>
<string name="no_changelog">No changelog available.</string>
```

---

## 13. GitHub Releases Setup

For this system to work, your GitHub release must:

1. **Have a tag** that is a semver string, optionally prefixed with `v` (e.g. `v1.2.3` or `1.2.3`)
2. **Have at least one `.apk` asset** uploaded to the release
3. **Optionally have a body** (release notes in Markdown) — this is what appears in the changelog dialog

### Creating a release

```bash
# Tag your release
git tag v1.3.0
git push origin v1.3.0
```

Then on GitHub:

1. Go to **Releases** → **Draft a new release**
2. Select the tag `v1.3.0`
3. Write your changelog in the body (Markdown)
4. Upload your signed `.apk` file as an asset
5. Publish

### Example release structure

```txt
Tag:    v1.3.0
Title:  Version 1.3.0
Body:   ## What's New
        - Added dark mode support
        - Fixed crash on startup
        - Improved download speed

Assets: yourapp-release-1.3.0.apk (15.2 MB)
```

---

## 14. How the GitHub API Response Maps to the Code

The API endpoint `GET /repos/{owner}/{repo}/releases/latest` returns JSON like this:

```json
{
  "tag_name": "v1.3.0",
  "body": "## What's New\n- Added dark mode support\n...",
  "assets": [
    {
      "name": "yourapp-release-1.3.0.apk",
      "size": 15938048,
      "browser_download_url": "https://github.com/.../yourapp-release-1.3.0.apk"
    }
  ]
}
```

| JSON field                       | Stored in                                           | Used for                                                 |
| -------------------------------- | --------------------------------------------------- | -------------------------------------------------------- |
| `tag_name`                       | `UpdateChecker.latestVersion` (after stripping `v`) | Version comparison, dialog title                         |
| `body`                           | `UpdateChecker.releaseBody`                         | Changelog dialog (rendered as Markdown)                  |
| `assets[0].name`                 | —                                                   | Matched with `.endsWith(".apk")` to find the right asset |
| `assets[0].size`                 | `UpdateChecker.apkSizeBytes`                        | Dialog "Download size: 15.2 MB"                          |
| `assets[0].browser_download_url` | `UpdateChecker.latestApkUrl`                        | Passed to `DownloadManager`                              |

---

## 15. Full State Machine Reference

```txt
                    ┌──────────────────────────────────┐
  App start         │                                  │
  ────────────────► │             IDLE                 │
                    │  "Tap to check for new versions" │
                    └──────────┬───────────────────────┘
                               │ user taps card
                               │ UpdateChecker.check()
                               │ finds newer version
                               ▼
                    ┌──────────────────────────────────┐
                    │        UPDATE_AVAILABLE          │
                    │  "Update available: 1.3.0 –      │
                    │   tap to install"                │
                    └──────────┬───────────────────────┘
                               │ user taps card
                               │ → changelog dialog shown
                               │ user taps "Update"
                               │ → startDownload()
                               ▼
                    ┌───────────────────────────────────┐
                    │          DOWNLOADING              │
                    │  bar: indeterminate → determinate │
                    │  "Initializing…" → "42%"          │
                    │  card: non-clickable              │
                    └──────────┬────────────────────────┘
                               │ STATUS_SUCCESSFUL
                               ▼
                    ┌───────────────────────────────────┐
                    │          INSTALLING               │
                    │  bar: indeterminate               │
                    │  "Installing update…"             │
                    │  card: non-clickable              │
                    └──────────┬────────────────────────┘
                               │ system installer launches
                               ▼
                         (app is replaced)


  Error at any point during DOWNLOADING:
      → state reverts to IDLE
      → progress bar hidden
      → card re-enabled
      → error message shown in subtitle
```

---

## 16. Customization Points

### Using a different release source (not GitHub)

Replace the `UpdateChecker.check()` coroutine body with your own API call. As long as you set these five fields, everything else works unchanged:

```kotlin
latestVersion = "1.3.0"
latestApkUrl = "https://your-server.com/app-1.3.0.apk"
releaseBody = "## Changelog\n- ..."
apkSizeBytes = 15938048L
updateAvailable = true
```

### Using a different Markdown renderer

Swap out the Markwon call in `showUpdateDialog()`:

```kotlin
// With Markwon (current)
val markwon = Markwon.create(ctx)
markwon.setMarkdown(changelogView, body)

// With any other library, or plain text
changelogView.text = body
```

### Periodic background checks

The current implementation only checks on app start. To add periodic checks, use `WorkManager`:

```kotlin
class UpdateCheckWorker(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        UpdateChecker.check(applicationContext)
        return Result.success()
    }
}

// Schedule (e.g. in Application.onCreate)
val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
    6, TimeUnit.HOURS
).build()
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "update_check",
    ExistingPeriodicWorkPolicy.KEEP,
    request
)
```

### Changing the polling interval

In `startDownloadPolling()`, change the `500` in `handler.postDelayed(this, 500)` to your preferred interval in milliseconds. Lower = smoother progress bar, higher = less CPU.

### Skipping the dialog (auto-download)

If you don't want a confirmation dialog, change the card click handler:

```kotlin
binding.updateCard.setOnClickListener {
    if (appUpdater.onUpdateTapped(requireContext())) {
        // Skip dialog, download immediately
        appUpdater.startDownload(requireContext())
    }
}
```

---

## 17. Troubleshooting

### "Install permission denied" / install does nothing

- Verify `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` is in your manifest
- On API 26+, the user must grant "Install unknown apps" permission for your app

### FileProvider crash: "Failed to find configured root"

- Verify `res/xml/file_paths.xml` contains `<external-files-path name="downloads" path="Download/" />`
- Verify the `<provider>` in your manifest uses `android:authorities="${applicationId}.fileprovider"` (matches the code's `"${context.packageName}.fileprovider"`)
- If using build flavors that change `applicationId`, `${applicationId}` and `${context.packageName}` may differ — make sure they match

### Download fails silently

- Check that `INTERNET` permission is in the manifest
- Check that the `browser_download_url` from GitHub is accessible (not behind auth)
- On some devices, `DownloadManager` silently fails if external storage isn't available — use `getExternalFilesDir()` (which doesn't need the `WRITE_EXTERNAL_STORAGE` permission)

### Progress bar stays indeterminate

- This is normal while the download is initializing (pending status). The bar switches to determinate once `DownloadManager` reports `STATUS_RUNNING` with valid byte counts.
- Some servers don't send `Content-Length`, in which case `COLUMN_TOTAL_SIZE_BYTES` will be `-1` and the bar stays indeterminate for the entire download.

### GitHub API returns 403 (rate limited)

- Unauthenticated limit is 60 requests/hour per IP
- The code silently catches this and reports no update available
- If testing frequently, wait or add a GitHub token header

### Lambda label compile error

If you see `Unresolved label` when using `return@callback` in a property-assigned lambda, switch to anonymous function syntax:

```kotlin
// ✗ Error
obj.callback = { if (condition) return@callback }

// ✓ Works
obj.callback = fun() { if (condition) return }
```

See [§9 (Why fun() instead of lambda)](#why-fun-instead-of-lambda-) for details.
