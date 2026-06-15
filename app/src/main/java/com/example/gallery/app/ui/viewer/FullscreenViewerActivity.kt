package com.example.gallery.app.ui.viewer

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.gallery.app.databinding.ActivityFullscreenViewerBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FullscreenViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenViewerBinding

    companion object {
        const val EXTRA_URI  = "extra_uri"
        const val EXTRA_NAME = "extra_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Immersive fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri  = intent.getStringExtra(EXTRA_URI) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""

        binding.toolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Load full-resolution image
        Glide.with(this)
            .load(uri)
            .into(binding.photoView)

        // Toggle UI visibility on tap
        binding.photoView.setOnClickListener {
            val isVisible = binding.toolbar.visibility == View.VISIBLE
            binding.toolbar.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }
}
