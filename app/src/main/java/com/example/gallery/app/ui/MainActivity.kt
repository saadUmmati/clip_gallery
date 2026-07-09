package com.example.gallery.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.work.WorkManager
import com.example.gallery.app.R
import com.example.gallery.app.databinding.ActivityMainBinding
import com.example.gallery.app.ui.gallery.AlbumsFragment
import com.example.gallery.app.ui.gallery.GalleryFragment
import com.example.gallery.app.ui.optimize.OptimizeFragment
import com.example.gallery.app.viewmodel.GalleryViewModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val galleryViewModel: GalleryViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // On Android 14+, we can proceed if we have either full or partial access
        val isFullGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        val isPartialGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        } else false

        if (isFullGranted || isPartialGranted) {
            triggerScan()
        } else {
            showPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        observeScanState()
        checkPermissions()

        supportFragmentManager.addOnBackStackChangedListener {
            val hasBackStack = supportFragmentManager.backStackEntryCount > 0
            findViewById<android.widget.FrameLayout>(R.id.nav_host_fragment)?.visibility =
                if (hasBackStack) View.VISIBLE else View.GONE
        }
    }

    private fun setupNavigation() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.isUserInputEnabled = false

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_gallery -> {
                    binding.viewPager.currentItem = 0
                    true
                }
                R.id.nav_albums -> {
                    binding.viewPager.currentItem = 1
                    true
                }
                R.id.nav_optimize -> {
                    binding.viewPager.currentItem = 2
                    true
                }
                R.id.nav_vault -> {
                    val intent = android.content.Intent(this, com.example.gallery.app.ui.vault.VaultActivity::class.java)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position in 0..2) {
                    binding.bottomNavigation.menu.getItem(position).isChecked = true
                }
            }
        })
    }

    private fun observeScanState() {
        lifecycleScope.launch {
            galleryViewModel.scanState.collectLatest { state ->
                when (state) {
                    is GalleryViewModel.ScanState.Scanning -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is GalleryViewModel.ScanState.Done -> {
                        binding.progressBar.visibility = View.GONE
                        if (state.count > 0) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.photo_count, state.count),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is GalleryViewModel.ScanState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                    }
                    else -> binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = getRequiredPermissions()
        val hasAccess = permissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasAccess) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    fun checkPermissionsAndScan() {
        val permissions = getRequiredPermissions()
        val hasAccess = permissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (hasAccess) {
            triggerScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun triggerScan() {
        galleryViewModel.triggerScan(WorkManager.getInstance(applicationContext))
    }

    private fun showPermissionRationale() {
        Snackbar.make(
            binding.root,
            getString(R.string.permission_storage_rationale),
            Snackbar.LENGTH_INDEFINITE
        ).setAction(getString(R.string.permission_grant)) {
            checkPermissionsAndScan()
        }.show()
    }

    fun switchToOptimizeTab() {
        binding.bottomNavigation.selectedItemId = R.id.nav_optimize
    }

    fun switchToGalleryTab() {
        binding.bottomNavigation.selectedItemId = R.id.nav_gallery
    }

    fun switchToAlbumsTab() {
        binding.bottomNavigation.selectedItemId = R.id.nav_albums
    }

    inner class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> GalleryFragment()
            1 -> AlbumsFragment()
            2 -> OptimizeFragment()
            else -> GalleryFragment()
        }
    }
}
