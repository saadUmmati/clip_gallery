package com.example.gallery.app.ui.optimize

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gallery.app.R
import com.example.gallery.app.databinding.FragmentRecycleBinBinding
import com.example.gallery.app.ui.gallery.SimpleGalleryGridAdapter
import com.example.gallery.app.viewmodel.OptimizeViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecycleBinFragment : Fragment() {

    private var _binding: FragmentRecycleBinBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OptimizeViewModel by activityViewModels()
    private lateinit var adapter: SimpleGalleryGridAdapter
    private var pendingDeletionUris: List<String> = emptyList()

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            lifecycleScope.launch {
                viewModel.confirmDeletion(pendingDeletionUris)
                pendingDeletionUris = emptyList()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecycleBinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_restore_all -> {
                    val items = viewModel.recycleBinItems.value ?: emptyList()
                    if (items.isNotEmpty()) {
                        viewModel.undoRecycleBin(items.map { it.uri })
                    }
                    true
                }
                R.id.action_empty_bin -> {
                    showEmptyBinConfirmDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = SimpleGalleryGridAdapter(
            onItemClick = { _ -> },
            onLongPress = { _ -> },
            onSelectionToggle = { _ -> }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@RecycleBinFragment.adapter
        }
    }

    private fun observeViewModel() {
        viewModel.recycleBinItems.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showEmptyBinConfirmDialog() {
        val items = viewModel.recycleBinItems.value ?: return
        if (items.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_empty_title)
            .setMessage(R.string.confirm_empty_message)
            .setPositiveButton(R.string.empty_confirm) { _, _ ->
                pendingDeletionUris = items.map { it.uri }
                viewModel.emptyRecycleBin(deleteRequestLauncher)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
