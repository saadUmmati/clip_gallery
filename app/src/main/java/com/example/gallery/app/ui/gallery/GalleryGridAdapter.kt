package com.example.gallery.app.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.databinding.ItemGalleryThumbnailBinding

class GalleryGridAdapter(
    private val onItemClick: (MediaItemEntity) -> Unit,
    private val onLongPress: (MediaItemEntity) -> Unit,
    private val onSelectionToggle: (MediaItemEntity) -> Unit
) : PagingDataAdapter<MediaItemEntity, GalleryGridAdapter.ThumbnailViewHolder>(DiffCallback) {

    var selectedUris: Set<String> = emptySet()
        set(value) {
            val old = field
            field = value
            snapshot().forEachIndexed { index, item ->
                if (item != null) {
                    val wasSelected = old.contains(item.uri)
                    val isSelected = value.contains(item.uri)
                    if (wasSelected != isSelected) {
                        notifyItemChanged(index, PAYLOAD_SELECTION)
                    }
                }
            }
        }

    var selectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemGalleryThumbnailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    override fun onBindViewHolder(
        holder: ThumbnailViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val item = getItem(position) ?: return
        if (payloads.isEmpty()) {
            holder.bind(item)
        } else {
            val bundle = payloads.filterIsInstance<Bundle>().firstOrNull()
            if (bundle != null) {
                holder.updatePayload(item, bundle)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    inner class ThumbnailViewHolder(
        private val binding: ItemGalleryThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundItem: MediaItemEntity? = null

        fun bind(item: MediaItemEntity) {
            boundItem = item

            // Load thumbnail via Glide
            Glide.with(binding.thumbnail)
                .load(item.uri)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(R.drawable.placeholder_image)
                .centerCrop()
                .into(binding.thumbnail)

            // Update badges
            updateBadges(item)

            // Update selection
            updateSelection(item)

            // Update click listeners
            if (selectionMode) {
                binding.root.setOnClickListener { onSelectionToggle(item) }
                binding.root.setOnLongClickListener(null)
            } else {
                binding.root.setOnClickListener { onItemClick(item) }
                binding.root.setOnLongClickListener {
                    onLongPress(item)
                    true
                }
            }
        }

        /**
         * Partial update using payloads — only refreshes metadata badges
         * WITHOUT reloading the thumbnail image, preventing flicker.
         */
        fun updatePayload(item: MediaItemEntity, bundle: Bundle) {
            boundItem = item

            if (bundle.containsKey(PAYLOAD_CHANGE_BADGES)) {
                updateBadges(item)
            }

            if (bundle.containsKey(PAYLOAD_SELECTION)) {
                updateSelection(item)
            }

            if (bundle.containsKey(PAYLOAD_CLICK_LISTENERS)) {
                if (selectionMode) {
                    binding.root.setOnClickListener { onSelectionToggle(item) }
                    binding.root.setOnLongClickListener(null)
                } else {
                    binding.root.setOnClickListener { onItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onLongPress(item)
                        true
                    }
                }
            }
        }

        private fun updateBadges(item: MediaItemEntity) {
            binding.badgeBestShot.visibility =
                if (item.isBestShot) View.VISIBLE else View.GONE
            binding.badgeBlurry.visibility =
                if (item.isBlurry) View.VISIBLE else View.GONE
            binding.overlay.visibility =
                if (item.isBlurry || item.isBestShot) View.VISIBLE else View.GONE
        }

        fun updateSelection(item: MediaItemEntity) {
            val isSelected = selectedUris.contains(item.uri)
            binding.selectionCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.selectionCheck.setImageResource(
                if (isSelected) R.drawable.ic_check_circle
                else R.drawable.ic_check_circle_outline
            )
            binding.selectionOverlay.visibility =
                if (isSelected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_CHANGE_BADGES = "change_badges"
        const val PAYLOAD_CLICK_LISTENERS = "click_listeners"

        val DiffCallback = object : DiffUtil.ItemCallback<MediaItemEntity>() {
            override fun areItemsTheSame(old: MediaItemEntity, new: MediaItemEntity) =
                old.uri == new.uri

            override fun areContentsTheSame(old: MediaItemEntity, new: MediaItemEntity) =
                old == new

            override fun getChangePayload(
                oldItem: MediaItemEntity,
                newItem: MediaItemEntity
            ): Any? {
                val bundle = Bundle()

                if (oldItem.isBestShot != newItem.isBestShot ||
                    oldItem.isBlurry != newItem.isBlurry ||
                    oldItem.clusterId != newItem.clusterId
                ) {
                    bundle.putBoolean(PAYLOAD_CHANGE_BADGES, true)
                }

                return if (bundle.isEmpty) null else bundle
            }
        }
    }
}
