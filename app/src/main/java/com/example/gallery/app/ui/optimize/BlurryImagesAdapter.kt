package com.example.gallery.app.ui.optimize

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
import com.example.gallery.app.databinding.ItemBlurryImageBinding

class BlurryImagesAdapter(
    private val onSelectionChanged: (uri: String, selected: Boolean) -> Unit
) : ListAdapter<MediaItemEntity, BlurryImagesAdapter.ViewHolder>(DiffCallback) {

    private val selectedUris = mutableSetOf<String>()

    fun updateSelections(uris: Set<String>) {
        val old = selectedUris.toSet()
        selectedUris.clear()
        selectedUris.addAll(uris)
        currentList.forEachIndexed { index, item ->
            val wasSelected = item.uri in old
            val isSelected = item.uri in uris
            if (wasSelected != isSelected) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlurryImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBlurryImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItemEntity) {
            Glide.with(binding.thumbnail)
                .load(item.uri)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(R.drawable.placeholder_image)
                .centerCrop()
                .into(binding.thumbnail)

            binding.fileName.text = item.fileName
            binding.sharpnessScore.text = "Sharpness: ${item.sharpnessScore?.let { "%.1f".format(it) } ?: "N/A"}"
            binding.fileSize.text = com.example.gallery.app.util.formatFileSize(item.sizeBytes)

            val isSelected = item.uri in selectedUris
            binding.checkbox.isChecked = isSelected
            binding.root.alpha = if (isSelected) 1f else 0.7f

            binding.root.setOnClickListener {
                val nowSelected = item.uri !in selectedUris
                if (nowSelected) selectedUris.add(item.uri) else selectedUris.remove(item.uri)
                binding.checkbox.isChecked = nowSelected
                binding.root.alpha = if (nowSelected) 1f else 0.7f
                onSelectionChanged(item.uri, nowSelected)
            }

            binding.checkbox.setOnClickListener {
                binding.root.performClick()
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MediaItemEntity>() {
        override fun areItemsTheSame(old: MediaItemEntity, new: MediaItemEntity) = old.uri == new.uri
        override fun areContentsTheSame(old: MediaItemEntity, new: MediaItemEntity) = old == new
    }
}
