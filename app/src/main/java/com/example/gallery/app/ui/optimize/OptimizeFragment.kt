package com.example.gallery.app.ui.optimize

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.databinding.FragmentOptimizeBinding
import com.example.gallery.app.databinding.ItemAlbumCardBinding
import com.example.gallery.app.ui.cluster.ClusterDetailFragment
import com.example.gallery.app.util.formatFileSize
import com.example.gallery.app.viewmodel.AIViewModel
import com.example.gallery.app.viewmodel.OptimizeViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class OptimizeFragment : Fragment() {

    private var _binding: FragmentOptimizeBinding? = null
    private val binding get() = _binding!!

    private val optimizeViewModel: OptimizeViewModel by activityViewModels()
    private val aiViewModel: AIViewModel by activityViewModels()

    private lateinit var clusterAdapter: ClusterAlbumAdapter
    private lateinit var blurryAdapter: BlurryImagesAdapter

    private var pendingDeletionUris: List<String> = emptyList()
    private var pendingTrashUris: List<String> = emptyList()
    private var pendingRestoreUris: List<String> = emptyList()

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            lifecycleScope.launch {
                optimizeViewModel.confirmDeletion(pendingDeletionUris)
                pendingDeletionUris = emptyList()
            }
        }
    }

    private val trashRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val allMedia = optimizeViewModel.allMedia.value ?: emptyList()
            optimizeViewModel.moveSelectedToRecycleBin(allMedia, pendingTrashUris)
            pendingTrashUris = emptyList()
        }
    }

    private val restoreRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            optimizeViewModel.undoRecycleBin(pendingRestoreUris)
            pendingRestoreUris = emptyList()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOptimizeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClusterGrid()
        setupBlurryList()
        setupButtons()
        observeViewModel()
    }

    private fun setupClusterGrid() {
        clusterAdapter = ClusterAlbumAdapter(
            onAlbumClick = { cluster ->
                val fragment = ClusterDetailFragment.newInstance(cluster.id)
                val container = requireActivity().findViewById<android.widget.FrameLayout>(R.id.nav_host_fragment)
                container.visibility = View.VISIBLE
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onAlbumLongClick = { cluster ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(cluster.label)
                    .setItems(arrayOf("Rename", "Delete Cluster")) { _, which ->
                        when (which) {
                            0 -> showRenameDialog(cluster)
                            1 -> {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Delete Cluster")
                                    .setMessage("Remove ${cluster.memberCount} images from this cluster? Images won't be deleted.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        optimizeViewModel.deleteCluster(cluster.id)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        binding.recyclerClusters.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = clusterAdapter
        }
    }

    private fun showRenameDialog(cluster: ClusterEntity) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(cluster.label)
            setSelectAllOnFocus(true)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Cluster")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    optimizeViewModel.renameCluster(cluster.id, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBlurryList() {
        blurryAdapter = BlurryImagesAdapter(
            onSelectionChanged = { uri, _ ->
                optimizeViewModel.toggleSelection(uri)
            }
        )
        binding.recyclerBlurry.adapter = blurryAdapter
    }

    private fun setupButtons() {
        binding.btnRunAi.setOnClickListener {
            val currentState = aiViewModel.processingState.value
            if (currentState is AIViewModel.AiState.Running) {
                aiViewModel.cancelProcessing(WorkManager.getInstance(requireContext().applicationContext))
            } else {
                val selected = optimizeViewModel.selectedUris.value
                if (selected.isNotEmpty()) {
                    aiViewModel.startProcessing(
                        WorkManager.getInstance(requireContext().applicationContext),
                        selected.toList()
                    )
                    optimizeViewModel.clearSelection()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Cluster All Images")
                        .setMessage("Process all gallery images with AI? This may take a while depending on your library size.")
                        .setPositiveButton("Start") { _, _ ->
                            (requireActivity() as? com.example.gallery.app.ui.MainActivity)?.checkPermissionsAndScan()
                            aiViewModel.startProcessingAll(
                                WorkManager.getInstance(requireContext().applicationContext)
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        binding.btnSelectAllBlurry.setOnClickListener {
            optimizeViewModel.blurryImages.value?.let { items ->
                optimizeViewModel.selectBlurryImages(items)
            }
        }

        binding.btnSelectDuplicates.setOnClickListener {
            val allMedia = optimizeViewModel.allMedia.value ?: return@setOnClickListener
            optimizeViewModel.selectDuplicates(allMedia)
        }

        binding.btnMoveToRecycleBin.setOnClickListener {
            val selected = optimizeViewModel.selectedUris.value.toList()
            if (selected.isEmpty()) {
                Snackbar.make(binding.root, getString(R.string.no_images_selected), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showConfirmDialog(selected)
        }

        binding.btnViewRecycleBin.setOnClickListener {
            val fragment = RecycleBinFragment()
            val container = requireActivity().findViewById<android.widget.FrameLayout>(R.id.nav_host_fragment)
            container.visibility = View.VISIBLE
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun showConfirmDialog(uris: List<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_move_title))
            .setMessage(getString(R.string.confirm_move_message, uris.size))
            .setPositiveButton(getString(R.string.move_confirm)) { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    pendingTrashUris = uris
                    lifecycleScope.launch {
                        optimizeViewModel.requestTrash(uris, trashRequestLauncher)
                    }
                } else {
                    val allMedia = optimizeViewModel.allMedia.value ?: emptyList()
                    optimizeViewModel.moveSelectedToRecycleBin(allMedia, uris)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun observeViewModel() {
        optimizeViewModel.allClusters.observe(viewLifecycleOwner) { clusters ->
            binding.clusterCount.text = "${clusters.size} AI groups found"
            binding.clusterSection.visibility =
                if (clusters.isEmpty()) View.GONE else View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                val albums = withContext(Dispatchers.IO) {
                    clusters.map { cluster ->
                        val previewUris = optimizeViewModel.getClusterPreviewUris(cluster.id)
                        ClusterDisplay(cluster, previewUris)
                    }
                }
                if (_binding != null) {
                    clusterAdapter.submitList(albums)
                }
            }
        }

        optimizeViewModel.blurryImages.observe(viewLifecycleOwner) { items ->
            blurryAdapter.submitList(items)
            binding.blurryCount.text = getString(R.string.blurry_images, items.size)
            binding.blurrySection.visibility =
                if (items.isEmpty()) View.GONE else View.VISIBLE
        }

        optimizeViewModel.reclaimableSize.observe(viewLifecycleOwner) { size ->
            size?.let {
                binding.reclaimableSize.text = getString(R.string.reclaimable_size, formatFileSize(it))
            }
        }

        optimizeViewModel.allMedia.observe(viewLifecycleOwner) { allItems ->
            val total = allItems.size
            if (total == 0) return@observe

            val blurryCount = allItems.count { it.isBlurry }
            val duplicateCount = allItems.count { !it.isBestShot && it.clusterId != null }
            val sharpCount = total - blurryCount

            binding.sharpCount.text = sharpCount.toString()
            binding.blurryCountQuality.text = blurryCount.toString()
            binding.duplicateCount.text = duplicateCount.toString()
            binding.qualityCard.visibility = View.VISIBLE
        }

        optimizeViewModel.recycleBinCount.observe(viewLifecycleOwner) { count ->
            binding.recycleBinBadge.text = count.toString()
            binding.recycleBinBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            optimizeViewModel.deleteState.collectLatest { state ->
                when (state) {
                    is OptimizeViewModel.DeleteState.UndoReady -> {
                        showUndoSnackbar(state.uris)
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            optimizeViewModel.selectedUris.collectLatest { selected ->
                val count = selected.size
                binding.selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
                binding.selectedCount.text = getString(R.string.selected_count, count)
                binding.btnMoveToRecycleBin.isEnabled = count > 0
                blurryAdapter.updateSelections(selected)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            aiViewModel.processingState.collectLatest { state ->
                when (state) {
                    is AIViewModel.AiState.Running -> {
                        binding.aiProgressBar.visibility = View.VISIBLE
                        binding.aiStatusText.visibility = View.VISIBLE
                        if (state.total > 0) {
                            binding.aiProgressBar.isIndeterminate = false
                            binding.aiProgressBar.max = state.total
                            binding.aiProgressBar.progress = state.processed
                            binding.aiStatusText.text = "${state.processed}/${state.total} — ${state.status}"
                        } else {
                            binding.aiProgressBar.isIndeterminate = true
                            binding.aiStatusText.text = state.status
                        }
                        binding.btnRunAi.text = "Cancel"
                        binding.btnRunAi.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.danger_red))
                    }
                    is AIViewModel.AiState.Done -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.VISIBLE
                        binding.aiStatusText.text = "${state.clusters} AI groups created"
                        binding.btnRunAi.text = getString(R.string.run_ai_on_selected)
                        binding.btnRunAi.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_blue))
                    }
                    is AIViewModel.AiState.Error -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.VISIBLE
                        binding.aiStatusText.text = getString(R.string.ai_error_prefix, state.message)
                        binding.btnRunAi.text = getString(R.string.run_ai_on_selected)
                        binding.btnRunAi.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_blue))
                    }
                    else -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.GONE
                        binding.btnRunAi.text = getString(R.string.run_ai_on_selected)
                        binding.btnRunAi.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_blue))
                    }
                }
            }
        }
    }

    private fun showUndoSnackbar(uris: List<String>) {
        Snackbar.make(
            binding.root,
            getString(R.string.moved_to_bin, uris.size),
            5000
        ).setAction(getString(R.string.undo)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pendingRestoreUris = uris
                lifecycleScope.launch {
                    optimizeViewModel.requestUntrash(uris, restoreRequestLauncher)
                }
            } else {
                optimizeViewModel.undoRecycleBin(uris)
            }
        }.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(snackbar: Snackbar, event: Int) {
                if (event != DISMISS_EVENT_ACTION) {
                    optimizeViewModel.confirmUndoExpired()
                }
            }
        }).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class ClusterDisplay(
    val cluster: ClusterEntity,
    val previewUris: List<String>
)

class ClusterAlbumAdapter(
    private val onAlbumClick: (ClusterEntity) -> Unit,
    private val onAlbumLongClick: (ClusterEntity) -> Unit
) : ListAdapter<ClusterDisplay, ClusterAlbumAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlbumCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemAlbumCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: ClusterDisplay) {
            binding.albumName.text = album.cluster.label
            binding.albumCount.text = "${album.cluster.memberCount} photos"

            val imageViews = listOf(
                binding.gridTopLeft, binding.gridTopRight,
                binding.gridBottomLeft, binding.gridBottomRight
            )

            for (i in imageViews.indices) {
                val iv = imageViews[i]
                if (i < album.previewUris.size) {
                    iv.visibility = View.VISIBLE
                    Glide.with(iv)
                        .load(album.previewUris[i])
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_image)
                        .into(iv)
                } else {
                    iv.visibility = View.GONE
                }
            }

            binding.root.setOnClickListener { onAlbumClick(album.cluster) }
            binding.root.setOnLongClickListener { onAlbumLongClick(album.cluster); true }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ClusterDisplay>() {
        override fun areItemsTheSame(old: ClusterDisplay, new: ClusterDisplay) =
            old.cluster.id == new.cluster.id
        override fun areContentsTheSame(old: ClusterDisplay, new: ClusterDisplay) = old == new
    }
}
