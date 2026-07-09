package com.example.gallery.app.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.databinding.FragmentGalleryBinding
import com.example.gallery.app.ui.viewer.FullscreenViewerActivity
import com.example.gallery.app.ui.vault.VaultActivity
import com.example.gallery.app.viewmodel.AIViewModel
import com.example.gallery.app.viewmodel.GalleryViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by activityViewModels()
    private val aiViewModel: AIViewModel by activityViewModels()
    private lateinit var adapter: GalleryGridAdapter
    private lateinit var timelineAdapter: TimelineAdapter
    private var isTimelineView = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupTimelineView()
        setupSearch()
        setupFab()
        setupVaultButton()
        setupShareButton()
        setupViewToggle()
        setupFolderFilter()
        setupEmptyStateCTA()
        observeViewModel()
    }

    private fun setupEmptyStateCTA() {
        binding.btnGrantAccess.setOnClickListener {
            (requireActivity() as? com.example.gallery.app.ui.MainActivity)?.checkPermissionsAndScan()
        }
    }

    private fun setupRecyclerView() {
        adapter = createGalleryAdapter()

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@GalleryFragment.adapter
            setHasFixedSize(true)
        }

        // Search result uses the same adapter — paging data handles filtering
        binding.searchResultRecyclerView.visibility = View.GONE
    }

    private fun setupTimelineView() {
        timelineAdapter = TimelineAdapter { item ->
            val intent = Intent(requireContext(), FullscreenViewerActivity::class.java).apply {
                putExtra(FullscreenViewerActivity.EXTRA_URI, item.uri)
            }
            startActivity(intent)
        }

        binding.timelineRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = this@GalleryFragment.timelineAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupViewToggle() {
        binding.btnToggleView.setOnClickListener {
            isTimelineView = !isTimelineView
            if (isTimelineView) {
                binding.recyclerView.visibility = View.GONE
                binding.timelineRecyclerView.visibility = View.VISIBLE
                binding.btnToggleView.setIconResource(R.drawable.ic_gallery)
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.timelineRecyclerView.visibility = View.GONE
                binding.btnToggleView.setIconResource(R.drawable.ic_timeline)
            }
        }
    }

    private fun createGalleryAdapter(): GalleryGridAdapter {
        return GalleryGridAdapter(
            onItemClick = { item, view ->
                if (viewModel.selectionMode.value) {
                    viewModel.toggleSelection(item.uri)
                } else {
                    val intent = Intent(requireContext(), FullscreenViewerActivity::class.java).apply {
                        putExtra(FullscreenViewerActivity.EXTRA_URI, item.uri)
                        putExtra("transition_name", view.transitionName)
                    }
                    val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                        requireActivity(),
                        view,
                        view.transitionName
                    )
                    startActivity(intent, options.toBundle())
                }
            },
            onLongPress = { item ->
                viewModel.enterSelectionMode(item.uri)
            },
            onSelectionToggle = { item ->
                viewModel.toggleSelection(item.uri)
            }
        )
    }

    private fun setupFab() {
        binding.btnSendToAi.setOnClickListener {
            val selected = viewModel.selectedUris.value
            if (selected.isEmpty()) return@setOnClickListener

            val uris = selected.toList()
            aiViewModel.startProcessing(
                androidx.work.WorkManager.getInstance(requireContext().applicationContext),
                uris
            )

            (requireActivity() as? com.example.gallery.app.ui.MainActivity)?.switchToOptimizeTab()
            viewModel.clearSelection()
        }
    }

    private fun setupVaultButton() {
        binding.btnMoveToVault.setOnClickListener {
            val selected = viewModel.selectedUris.value
            if (selected.isEmpty()) return@setOnClickListener

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vault_move_to)
                .setMessage(getString(R.string.confirm_vault_move, selected.size))
                .setPositiveButton(R.string.move_confirm) { _, _ ->
                    val uris = selected.toList()
                    val intent = Intent(requireContext(), VaultActivity::class.java)
                    intent.putStringArrayListExtra("vault_move_uris", ArrayList(uris))
                    startActivity(intent)
                    viewModel.clearSelection()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupShareButton() {
        binding.btnShare.setOnClickListener {
            val selected = viewModel.selectedUris.value.toList()
            if (selected.size == 1) {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(selected[0]))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_via)))
            } else if (selected.size > 1) {
                val uris = selected.map { android.net.Uri.parse(it) } as ArrayList<android.net.Uri>
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_via)))
            }
        }
    }

    private fun setupSearch() {
        binding.searchView.setupWithSearchBar(binding.searchBar)
        binding.searchView.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.updateSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupFolderFilter() {
        viewModel.allFolders.observe(viewLifecycleOwner) { folders ->
            if (folders.size <= 1) {
                binding.folderFilterScroll.visibility = View.GONE
                return@observe
            }

            binding.folderFilterScroll.visibility = View.VISIBLE
            binding.folderChipGroup.removeAllViews()

            // "All" chip
            val allChip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = "All"
                isCheckable = true
                isChecked = viewModel.folderFilter.value == null
                setOnClickListener { viewModel.setFolderFilter(null) }
            }
            binding.folderChipGroup.addView(allChip)

            for (folder in folders) {
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = "${folder.folder} (${folder.imageCount})"
                    isCheckable = true
                    isChecked = viewModel.folderFilter.value == folder.folder
                    setOnClickListener { viewModel.setFolderFilter(folder.folder) }
                }
                binding.folderChipGroup.addView(chip)
            }
        }
    }

    private fun observeViewModel() {
        // Collect paging data from Room — automatically invalidates on DB writes
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pagedMedia.collectLatest { pagingData ->
                    adapter.submitData(pagingData)
                }
            }
        }

        // Show empty state when paging data is empty
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                adapter.loadStateFlow.collectLatest { loadStates ->
                    val refresh = loadStates.refresh
                    val isListEmpty = refresh is androidx.paging.LoadState.NotLoading && adapter.itemCount == 0
                    binding.emptyState.visibility = if (isListEmpty) View.VISIBLE else View.GONE
                    binding.recyclerView.visibility = if (isListEmpty) View.GONE else View.VISIBLE
                }
            }
        }

        viewModel.totalCount.observe(viewLifecycleOwner) { count ->
            binding.photoCount.text = getString(R.string.photo_count, count)
        }

        // Observe search status
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchStatus.collectLatest { status ->
                binding.photoCount.text = if (status.isNotEmpty()) {
                    status
                } else {
                    getString(R.string.photo_count, viewModel.totalCount.value ?: 0)
                }
            }
        }

        // Observe selection mode
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectionMode.collectLatest { isSelectionMode ->
                adapter.selectionMode = isSelectionMode
                if (!isSelectionMode) {
                    adapter.selectedUris = emptySet()
                    binding.selectionBar.visibility = View.GONE
                }
            }
        }

        // Select All button
        binding.btnSelectAll.setOnClickListener {
            val currentItems = adapter.snapshot().items
            viewModel.selectAllVisible(currentItems)
        }

        // Observe selected URIs
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedUris.collectLatest { uris ->
                adapter.selectedUris = uris

                if (uris.isNotEmpty()) {
                    binding.selectedCount.text = getString(R.string.selected_count, uris.size)
                    if (binding.selectionBar.visibility != View.VISIBLE) {
                        binding.selectionBar.visibility = View.VISIBLE
                        binding.selectionBar.alpha = 0f
                        binding.selectionBar.translationY = 100f
                        binding.selectionBar.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(250)
                            .start()
                    }
                } else {
                    if (binding.selectionBar.visibility == View.VISIBLE) {
                        binding.selectionBar.animate()
                            .alpha(0f)
                            .translationY(100f)
                            .setDuration(200)
                            .withEndAction { binding.selectionBar.visibility = View.GONE }
                            .start()
                    }
                }
            }
        }

        // Observe AI processing state
        viewLifecycleOwner.lifecycleScope.launch {
            aiViewModel.processingState.collectLatest { state ->
                when (state) {
                    is AIViewModel.AiState.Running -> {
                        binding.btnSendToAi.isEnabled = false
                    }
                    is AIViewModel.AiState.Idle, is AIViewModel.AiState.Done, is AIViewModel.AiState.Error -> {
                        binding.btnSendToAi.isEnabled = true
                    }
                }
            }
        }

        // Build timeline from all items (still uses LiveData for full list)
        viewModel.allMedia.observe(viewLifecycleOwner) { items ->
            if (items.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val timelineGroups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val engine = com.example.gallery.app.ai.TimelineEngine()
                        engine.buildTimeline(items)
                    }
                    val timelineItems = TimelineAdapter.fromGroups(timelineGroups)
                    timelineAdapter.submitList(timelineItems)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
