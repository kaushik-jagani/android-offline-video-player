package com.example.videoplayer.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.videoplayer.R
import com.example.videoplayer.databinding.ActivityMainBinding
import com.example.videoplayer.ui.video_list.VideoListActivity
import com.example.videoplayer.utils.Constants
import com.example.videoplayer.utils.PermissionHelper
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

    private fun setupRecyclerView() {
        folderAdapter = FolderAdapter { folder ->
            val intent = Intent(this, VideoListActivity::class.java).apply {
                putExtra(Constants.EXTRA_FOLDER_PATH, folder.path)
                putExtra(Constants.EXTRA_FOLDER_NAME, folder.name)
            }
            startActivity(intent)
        }

        binding.rvFolders.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = folderAdapter
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
}
