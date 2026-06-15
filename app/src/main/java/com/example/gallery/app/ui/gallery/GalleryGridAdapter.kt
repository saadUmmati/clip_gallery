package com.example.gallery.app.ui.gallery


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.gallery.app.R
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.databinding.ItemGalleryThumbnailBinding

class GalleryGridAdapter(
    private val onItemClick: (MediaItemEntity) -> Unit
) : ListAdapter<MediaItemEntity, GalleryGridAdapter.ThumbnailViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemGalleryThumbnailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ThumbnailViewHolder(
        private val binding: ItemGalleryThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItemEntity) {
            // Load thumbnail with Glide — hardware bitmaps for performance
            Glide.with(binding.thumbnail)
                .load(item.uri)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(R.drawable.placeholder_image)
                .centerCrop()
                .into(binding.thumbnail)

            // Visual badges
            binding.badgeBestShot.visibility =
                if (item.isBestShot) View.VISIBLE else View.GONE

            binding.badgeBlurry.visibility =
                if (item.isBlurry) View.VISIBLE else View.GONE

            binding.overlay.visibility =
                if (item.isBlurry || item.isBestShot) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MediaItemEntity>() {
        override fun areItemsTheSame(old: MediaItemEntity, new: MediaItemEntity) =
            old.uri == new.uri

        override fun areContentsTheSame(old: MediaItemEntity, new: MediaItemEntity) =
            old == new
    }
}
