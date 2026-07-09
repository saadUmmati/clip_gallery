package com.example.gallery.app.ui.cluster


import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.databinding.FragmentClusterDetailBinding
import com.example.gallery.app.databinding.ItemClusterCardBinding
import com.example.gallery.app.databinding.ItemGalleryThumbnailBinding
import com.example.gallery.app.ui.viewer.FullscreenViewerActivity
import com.example.gallery.app.viewmodel.ClusterDetailViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────
// Cluster list adapter (used in OptimizeFragment)
// ──────────────────────────────────────────────────
class ClusterListAdapter(
    private val onClusterClick: (ClusterEntity) -> Unit
) : ListAdapter<ClusterEntity, ClusterListAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClusterCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemClusterCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(cluster: ClusterEntity) {
            binding.clusterLabel.text = cluster.label
            binding.memberCount.text = "${cluster.memberCount} photos"
            binding.blurryCount.text = "${cluster.blurryCount} blurry"
            binding.blurryCount.visibility =
                if (cluster.blurryCount > 0) View.VISIBLE else View.GONE

            // Load best shot thumbnail
            cluster.bestShotUri?.let { uri ->
                Glide.with(binding.bestShotThumb)
                    .load(uri)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .centerCrop()
                    .placeholder(R.drawable.placeholder_image)
                    .into(binding.bestShotThumb)
            }

            binding.root.setOnClickListener { onClusterClick(cluster) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ClusterEntity>() {
        override fun areItemsTheSame(old: ClusterEntity, new: ClusterEntity) = old.id == new.id
        override fun areContentsTheSame(old: ClusterEntity, new: ClusterEntity) = old == new
    }
}

// ──────────────────────────────────────────────────
// Cluster detail fragment — shows all members
// ──────────────────────────────────────────────────
@AndroidEntryPoint
class ClusterDetailFragment : Fragment() {

    private var _binding: FragmentClusterDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ClusterDetailViewModel by viewModels()
    private lateinit var adapter: ClusterMembersAdapter

    companion object {
        private const val ARG_CLUSTER_ID = "cluster_id"

        fun newInstance(clusterId: Int) = ClusterDetailFragment().apply {
            arguments = Bundle().also { it.putInt(ARG_CLUSTER_ID, clusterId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClusterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clusterId = arguments?.getInt(ARG_CLUSTER_ID) ?: return

        adapter = ClusterMembersAdapter(
            onItemClick = { item ->
                val intent = Intent(requireContext(), FullscreenViewerActivity::class.java).apply {
                    putExtra(FullscreenViewerActivity.EXTRA_URI, item.uri)
                    putExtra(FullscreenViewerActivity.EXTRA_CLUSTER_ID, clusterId)
                }
                startActivity(intent)
            },
            onLongPress = { item ->
                viewModel.enterSelectionMode(item.uri)
            },
            onSelectionToggle = { item ->
                viewModel.toggleSelection(item.uri)
            }
        )
        binding.recyclerMembers.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerMembers.adapter = adapter

        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.selectionMode.value) {
                viewModel.clearSelection()
            } else {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }

        binding.btnRemoveFromCluster.setOnClickListener {
            val count = viewModel.selectedUris.value.size
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove from Cluster")
                .setMessage("Remove $count photo(s) from this cluster? They won't be deleted, just ungrouped.")
                .setPositiveButton("Remove") { _, _ ->
                    viewModel.removeFromCluster()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        viewModel.loadCluster(clusterId)
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.clusterMembers.observe(viewLifecycleOwner) { members ->
            adapter.submitList(members)
            binding.toolbar.subtitle = getString(R.string.photos_count, members.size)

            val bestShot = members.firstOrNull { it.isBestShot }
            bestShot?.let { item ->
                Glide.with(binding.bestShotImage)
                    .load(item.uri)
                    .centerCrop()
                    .into(binding.bestShotImage)
                binding.bestShotCard.visibility = View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectionMode.collectLatest { isSelectionMode ->
                adapter.selectionMode = isSelectionMode
                binding.selectionBar.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                binding.toolbar.title = if (isSelectionMode) "Select photos" else "Cluster"
                if (!isSelectionMode) adapter.selectedUris = emptySet()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedUris.collectLatest { uris ->
                adapter.selectedUris = uris
                binding.selectedCount.text = "${uris.size} selected"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ──────────────────────────────────────────────────
// Adapter for cluster member grid
// ──────────────────────────────────────────────────
class ClusterMembersAdapter(
    private val onItemClick: (MediaItemEntity) -> Unit = {},
    private val onLongPress: (MediaItemEntity) -> Unit = {},
    private val onSelectionToggle: (MediaItemEntity) -> Unit = {}
) : ListAdapter<MediaItemEntity, ClusterMembersAdapter.ViewHolder>(DiffCallback) {

    var selectionMode = false
    var selectedUris: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryThumbnailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemGalleryThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItemEntity) {
            Glide.with(binding.thumbnail)
                .load(item.uri)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .placeholder(R.drawable.placeholder_image)
                .into(binding.thumbnail)

            binding.badgeBestShot.visibility = if (item.isBestShot) View.VISIBLE else View.GONE
            binding.badgeBlurry.visibility = if (item.isBlurry) View.VISIBLE else View.GONE
            binding.overlay.visibility =
                if (item.isBlurry || item.isBestShot) View.VISIBLE else View.GONE

            if (selectionMode) {
                binding.selectionCheck.visibility =
                    if (item.uri in selectedUris) View.VISIBLE else View.GONE
                binding.selectionOverlay.visibility =
                    if (item.uri in selectedUris) View.VISIBLE else View.GONE
                binding.thumbnail.alpha = if (item.uri in selectedUris) 0.6f else 1.0f
            } else {
                binding.selectionCheck.visibility = View.GONE
                binding.selectionOverlay.visibility = View.GONE
                binding.thumbnail.alpha = 1.0f
            }

            binding.root.setOnClickListener {
                if (selectionMode) onSelectionToggle(item) else onItemClick(item)
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) onLongPress(item)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MediaItemEntity>() {
        override fun areItemsTheSame(old: MediaItemEntity, new: MediaItemEntity) = old.uri == new.uri
        override fun areContentsTheSame(old: MediaItemEntity, new: MediaItemEntity) = old == new
    }
}
