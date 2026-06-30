package com.example.gallery.app.ui.optimize

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.example.gallery.app.R
import com.example.gallery.app.databinding.FragmentOptimizeBinding
import com.example.gallery.app.ui.cluster.ClusterListAdapter
import com.example.gallery.app.util.formatFileSize
import com.example.gallery.app.viewmodel.AIViewModel
import com.example.gallery.app.viewmodel.OptimizeViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OptimizeFragment : Fragment() {

    private var _binding: FragmentOptimizeBinding? = null
    private val binding get() = _binding!!

    private val optimizeViewModel: OptimizeViewModel by activityViewModels()
    private val aiViewModel: AIViewModel by activityViewModels()

    private lateinit var clusterAdapter: ClusterListAdapter
    private lateinit var blurryAdapter: BlurryImagesAdapter

    private var pendingDeletionUris: List<String> = emptyList()

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOptimizeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClusterList()
        setupBlurryList()
        setupButtons()
        observeViewModel()
    }

    private fun setupClusterList() {
        clusterAdapter = ClusterListAdapter(
            onClusterClick = { cluster ->
                val fragment = com.example.gallery.app.ui.cluster.ClusterDetailFragment.newInstance(cluster.id)
                val container = requireActivity().findViewById<android.widget.FrameLayout>(R.id.nav_host_fragment)
                container.visibility = View.VISIBLE
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerClusters.adapter = clusterAdapter
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
            aiViewModel.startProcessing(WorkManager.getInstance(requireContext().applicationContext))
        }

        binding.btnSelectAllBlurry.setOnClickListener {
            optimizeViewModel.blurryImages.value?.let { items ->
                optimizeViewModel.selectBlurryImages(items)
            }
        }

        binding.btnSelectDuplicates.setOnClickListener {
            val clusters = optimizeViewModel.allClusters.value ?: return@setOnClickListener
            val allMedia = optimizeViewModel.allMedia.value ?: return@setOnClickListener
            optimizeViewModel.selectDuplicates(clusters, allMedia)
        }

        binding.btnMoveToRecycleBin.setOnClickListener {
            val selected = optimizeViewModel.selectedUris.value
            if (selected.isEmpty()) {
                Snackbar.make(binding.root, getString(R.string.no_images_selected), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showConfirmDialog(selected.size)
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

    private fun showConfirmDialog(count: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_move_title))
            .setMessage(getString(R.string.confirm_move_message, count))
            .setPositiveButton(getString(R.string.move_confirm)) { _, _ ->
                val allMedia = optimizeViewModel.allMedia.value ?: emptyList()
                optimizeViewModel.moveSelectedToRecycleBin(allMedia)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun observeViewModel() {
        optimizeViewModel.allClusters.observe(viewLifecycleOwner) { clusters ->
            clusterAdapter.submitList(clusters)
            binding.clusterCount.text = getString(R.string.clusters_found_count, clusters.size)
            binding.clusterSection.visibility =
                if (clusters.isEmpty()) View.GONE else View.VISIBLE
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

        // Quality breakdown
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
                        binding.aiStatusText.text = state.status
                        binding.btnRunAi.isEnabled = false
                    }
                    is AIViewModel.AiState.Done -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.VISIBLE
                        binding.aiStatusText.text = getString(R.string.clusters_found_count, state.clusters)
                        binding.btnRunAi.isEnabled = true
                    }
                    is AIViewModel.AiState.Error -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.VISIBLE
                        binding.aiStatusText.text = getString(R.string.ai_error_prefix, state.message)
                        binding.btnRunAi.isEnabled = true
                    }
                    else -> {
                        binding.aiProgressBar.visibility = View.GONE
                        binding.aiStatusText.visibility = View.GONE
                        binding.btnRunAi.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showUndoSnackbar(uris: List<String>) {
        Snackbar.make(
            binding.root,
            getString(R.string.moved_to_bin, uris.size),
            5000 // 5 seconds
        ).setAction(getString(R.string.undo)) {
            optimizeViewModel.undoRecycleBin(uris)
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
