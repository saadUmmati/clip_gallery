package com.example.gallery.app.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.databinding.ActivityFullscreenViewerBinding
import com.example.gallery.app.security.VaultCryptoManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FullscreenViewerActivity : AppCompatActivity(), ImageInteractionListener {

    private lateinit var binding: ActivityFullscreenViewerBinding
    private val viewModel: ViewerViewModel by viewModels()
    private var isSystemBarsVisible = false
    private var isToolbarVisible = true
    private var adapter: ViewerPagerAdapter? = null
    private var currentImages: List<ViewerImage> = emptyList()
    private var initialUri: String? = null
    private var hasSetInitialPosition = false

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_CLUSTER_ID = "extra_cluster_id"
        const val EXTRA_IS_VAULT = "extra_is_vault"
        const val EXTRA_IS_BLURRY = "extra_is_blurry"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.sharedElementEnterTransition = com.google.android.material.transition.platform.MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 300L
        }
        window.sharedElementReturnTransition = com.google.android.material.transition.platform.MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 250L
        }

        postponeEnterTransition()
        binding.viewPager.keepScreenOn = true
        hideSystemBars()

        initialUri = intent.getStringExtra(EXTRA_URI)
        val position = intent.getIntExtra(EXTRA_POSITION, -1)
        val clusterId = intent.getIntExtra(EXTRA_CLUSTER_ID, -1).takeIf { it != -1 }
        val isVault = intent.getBooleanExtra(EXTRA_IS_VAULT, false)
        val isBlurry = intent.getBooleanExtra(EXTRA_IS_BLURRY, false)

        val transitionName = intent.getStringExtra("transition_name")
        binding.viewPager.transitionName = transitionName

        setupToolbar()
        observeViewModel(position)

        viewModel.loadMedia(clusterId, isVault, isBlurry)
    }

    override fun onImageSingleTap() {
        toggleSystemBars()
    }

    override fun onImageSwipeUp() {
        showMetadata()
    }

    private fun observeViewModel(initialPosition: Int) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mediaItems.collectLatest { items ->
                    if (items.isNotEmpty()) {
                        setupWithItems(items, initialPosition)
                    }
                }
            }
        }
    }

    private fun setupWithItems(items: List<MediaItemEntity>, initialPosition: Int) {
        val isVault = intent.getBooleanExtra(EXTRA_IS_VAULT, false)
        currentImages = items.map { item ->
            ViewerImage(
                uri = item.uri,
                filePath = if (isVault) {
                    VaultCryptoManager.getEncryptedFilePath(this, item.uri)
                } else {
                    item.filePath
                },
                width = item.width,
                height = item.height,
                name = item.fileName,
                sizeBytes = item.sizeBytes,
                mimeType = item.mimeType
            )
        }

        val adapter = ViewerPagerAdapter(this, currentImages)
        this.adapter = adapter
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 1

        if (!hasSetInitialPosition) {
            val pos = if (initialPosition != -1) {
                initialPosition
            } else if (initialUri != null) {
                val index = currentImages.indexOfFirst { it.uri == initialUri }
                if (index != -1) index else 0
            } else {
                0
            }

            if (pos in currentImages.indices) {
                binding.viewPager.setCurrentItem(pos, false)
                binding.toolbar.title = adapter.getNameAt(pos)
                updatePageCounter(pos + 1, currentImages.size)
            }
            hasSetInitialPosition = true
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                super.onPageSelected(pos)
                binding.toolbar.title = adapter.getNameAt(pos)
                updatePageCounter(pos + 1, currentImages.size)
            }
        })
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_viewer)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    shareCurrentImage()
                    true
                }
                R.id.action_info -> {
                    showMetadata()
                    true
                }
                R.id.action_delete -> {
                    deleteCurrentImage()
                    true
                }
                else -> false
            }
        }
    }

    private fun deleteCurrentImage() {
        val pos = binding.viewPager.currentItem
        if (pos !in currentImages.indices) return
        val image = currentImages[pos]
        val isVault = intent.getBooleanExtra(EXTRA_IS_VAULT, false)

        val messageRes = if (isVault) R.string.confirm_vault_delete else R.string.confirm_move_message
        val titleRes = if (isVault) R.string.vault_delete else R.string.confirm_move_title

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(titleRes))
            .setMessage(getString(messageRes, 1))
            .setPositiveButton(getString(R.string.move_confirm)) { _, _ ->
                val entity = viewModel.mediaItems.value.firstOrNull { it.uri == image.uri }
                if (entity != null) {
                    viewModel.deleteItem(entity, isVault)
                    if (currentImages.size <= 1) {
                        finish()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showMetadata() {
        val pos = binding.viewPager.currentItem
        if (pos !in currentImages.indices) return
        val image = currentImages[pos]

        val sheet = MetadataBottomSheet.newInstance(
            filePath = image.filePath,
            width = image.width,
            height = image.height,
            sizeBytes = image.sizeBytes,
            mimeType = image.mimeType,
            name = image.name
        )
        sheet.show(supportFragmentManager, MetadataBottomSheet.TAG)
    }

    private fun shareCurrentImage() {
        val pos = binding.viewPager.currentItem
        if (pos !in currentImages.indices) return
        val uri = android.net.Uri.parse(currentImages[pos].uri)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = currentImages[pos].mimeType ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)))
    }


    private fun updatePageCounter(current: Int, total: Int) {
        if (total <= 1) {
            binding.pageCounter.isVisible = false
            return
        }
        binding.pageCounter.isVisible = true
        binding.pageCounter.text = "$current / $total"
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        isSystemBarsVisible = false
        isToolbarVisible = false
        binding.toolbar.visibility = View.GONE
        binding.bottomActionsBar.visibility = View.GONE
        binding.pageCounter.alpha = 0f
    }

    private fun toggleSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = WindowInsetsCompat.Type.systemBars()

        if (isSystemBarsVisible) {
            controller.hide(bars)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            fadeOutToolbar()
            isSystemBarsVisible = false
        } else {
            controller.show(bars)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            fadeInToolbar()
            isSystemBarsVisible = true
        }
    }

    private fun fadeInToolbar() {
        isToolbarVisible = true
        binding.toolbar.visibility = View.VISIBLE
        binding.toolbar.alpha = 0f
        binding.toolbar.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.bottomActionsBar.visibility = View.VISIBLE
        binding.bottomActionsBar.alpha = 0f
        binding.bottomActionsBar.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (binding.pageCounter.isVisible) {
            binding.pageCounter.animate()
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun fadeOutToolbar() {
        isToolbarVisible = false
        binding.toolbar.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { binding.toolbar.visibility = View.GONE }
            .start()

        binding.bottomActionsBar.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { binding.bottomActionsBar.visibility = View.GONE }
            .start()

        binding.pageCounter.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
