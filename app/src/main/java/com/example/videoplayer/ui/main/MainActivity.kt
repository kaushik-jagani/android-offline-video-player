package com.example.videoplayer.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import com.example.videoplayer.R
import com.example.videoplayer.databinding.ActivityMainBinding
import com.example.videoplayer.ui.player.PlayerActivity
import com.example.videoplayer.ui.video_list.VideoListActivity
import com.example.videoplayer.utils.Constants
import com.example.videoplayer.utils.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main screen – displays a grid of folders that contain videos.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var historyHeaderAdapter: HomeHistoryHeaderAdapter

    // Modern permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.scanVideos()
        } else {
            // Check if "Don't ask again" was selected
            val shouldShowRationale = shouldShowRequestPermissionRationale(
                PermissionHelper.videoPermission()
            )
            if (!shouldShowRationale) {
                // User chose "Don't ask again" — guide them to Settings
                Snackbar.make(
                    binding.root,
                    R.string.permission_denied,
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }.show()
            } else {
                Snackbar.make(
                    binding.root,
                    R.string.permission_required,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        observeState()
        setupSwipeRefresh()
        requestPermissionAndScan()
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Video Player"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        historyHeaderAdapter = HomeHistoryHeaderAdapter { video ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(Constants.EXTRA_VIDEO_ID, video.id)
                putExtra(Constants.EXTRA_VIDEO_PATH, video.path)
                putExtra(Constants.EXTRA_VIDEO_TITLE, video.title)
                putExtra(Constants.EXTRA_START_POSITION, video.lastPosition)
                // Single-item playlist to keep PlayerActivity logic consistent.
                putExtra(Constants.EXTRA_PLAYLIST_PATHS, arrayListOf(video.path))
                putExtra(Constants.EXTRA_PLAYLIST_TITLES, arrayListOf(video.title))
                putExtra(Constants.EXTRA_PLAYLIST_IDS, longArrayOf(video.id))
                putExtra(Constants.EXTRA_PLAYLIST_INDEX, 0)
            }
            startActivity(intent)
        }

        folderAdapter = FolderAdapter { folder ->
            val intent = Intent(this, VideoListActivity::class.java).apply {
                putExtra(Constants.EXTRA_FOLDER_PATH, folder.path)
                putExtra(Constants.EXTRA_FOLDER_NAME, folder.name)
            }
            startActivity(intent)
        }

        binding.rvFolders.apply {
            val concatAdapter = ConcatAdapter(historyHeaderAdapter, folderAdapter)
            val glm = GridLayoutManager(this@MainActivity, 2)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // Header should span full width.
                    return if (position == 0 && historyHeaderAdapter.itemCount == 1) 2 else 1
                }
            }

            layoutManager = glm
            adapter = concatAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.scanVideos()
        }
    }

    // ── State Observation (StateFlow) ────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.folders.collectLatest { folders ->
                        folderAdapter.submitList(folders)
                    }
                }
                launch {
                    viewModel.history.collectLatest { history ->
                        historyHeaderAdapter.submit(history)
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                    }
                }
                launch {
                    viewModel.isEmpty.collectLatest { empty ->
                        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.scanError.collectLatest { error ->
                        error?.let {
                            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun requestPermissionAndScan() {
        if (PermissionHelper.hasVideoPermission(this)) {
            viewModel.scanVideos()
        } else {
            permissionLauncher.launch(PermissionHelper.videoPermission())
        }
    }

    private fun showAboutDialog() {
        val privacyUrl = getString(R.string.privacy_policy_url).trim()
        val versionText = getVersionText()

        val message = buildString {
            appendLine(getString(R.string.app_description))
            appendLine()
            appendLine(getString(R.string.version_label, versionText))
            if (privacyUrl.isBlank()) {
                appendLine()
                appendLine(getString(R.string.privacy_policy_missing_hint))
            }
        }.trim()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(message)
            .setNeutralButton(R.string.open_source_licenses) { _, _ ->
                showOpenSourceLicensesDialog()
            }
            .setNegativeButton(R.string.privacy_policy) { _, _ ->
                if (privacyUrl.isBlank()) {
                    Snackbar.make(binding.root, R.string.privacy_policy_missing_snackbar, Snackbar.LENGTH_LONG).show()
                } else {
                    openUrl(privacyUrl)
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun getVersionText(): String {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = info.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$versionName ($versionCode)"
        }.getOrElse { "?" }
    }

    private fun showOpenSourceLicensesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_source_licenses)
            .setMessage(getString(R.string.open_source_licenses_text))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Snackbar.make(binding.root, R.string.cant_open_link, Snackbar.LENGTH_LONG).show()
        }
    }
}
