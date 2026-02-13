package com.example.videoplayer.ui.video_list

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoplayer.databinding.ActivityVideoListBinding
import com.example.videoplayer.ui.player.PlayerActivity
import com.example.videoplayer.utils.Constants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows a list of videos that belong to a single folder.
 */
class VideoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoListBinding
    private val viewModel: VideoListViewModel by viewModels()
    private lateinit var videoAdapter: VideoAdapter

    private var folderPath: String = ""
    private var folderName: String = ""

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderPath = intent.getStringExtra(Constants.EXTRA_FOLDER_PATH) ?: ""
        folderName = intent.getStringExtra(Constants.EXTRA_FOLDER_NAME) ?: "Videos"

        setupToolbar()
        setupRecyclerView()
        observeState()

        viewModel.loadVideos(folderPath)
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = folderName
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { video ->
            val currentList = videoAdapter.currentList
            val index = currentList.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(Constants.EXTRA_VIDEO_ID, video.id)
                putExtra(Constants.EXTRA_VIDEO_PATH, video.path)
                putExtra(Constants.EXTRA_VIDEO_TITLE, video.title)
                putExtra(Constants.EXTRA_START_POSITION, video.lastPosition)
                // Pass playlist for next/previous navigation
                putExtra(Constants.EXTRA_PLAYLIST_PATHS, ArrayList(currentList.map { it.path }))
                putExtra(Constants.EXTRA_PLAYLIST_TITLES, ArrayList(currentList.map { it.title }))
                putExtra(Constants.EXTRA_PLAYLIST_IDS, currentList.map { it.id }.toLongArray())
                putExtra(Constants.EXTRA_PLAYLIST_INDEX, index)
            }
            startActivity(intent)
        }

        binding.rvVideos.apply {
            layoutManager = LinearLayoutManager(this@VideoListActivity)
            adapter = videoAdapter
            setHasFixedSize(true)
        }
    }

    // ── State Observation ────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videos.collectLatest { list ->
                        videoAdapter.submitList(list)
                    }
                }
                launch {
                    viewModel.isEmpty.collectLatest { empty ->
                        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
}
