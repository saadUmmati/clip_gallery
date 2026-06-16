package com.example.gallery.app.ui.gallery


import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gallery.app.R
import com.example.gallery.app.databinding.FragmentGalleryBinding
import com.example.gallery.app.ui.viewer.FullscreenViewerActivity
import com.example.gallery.app.viewmodel.GalleryViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by activityViewModels()
    private lateinit var adapter: GalleryGridAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = GalleryGridAdapter { item ->
            val intent = Intent(requireContext(), FullscreenViewerActivity::class.java).apply {
                putExtra(FullscreenViewerActivity.EXTRA_URI, item.uri)
                putExtra(FullscreenViewerActivity.EXTRA_NAME, item.fileName)
            }
            startActivity(intent)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@GalleryFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewModel.allMedia.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)

            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.totalCount.observe(viewLifecycleOwner) { count ->
            binding.photoCount.text = getString(R.string.photo_count, count)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
