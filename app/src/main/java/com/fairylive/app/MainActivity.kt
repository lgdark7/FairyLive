package com.fairylive.app

import com.fairylive.app.ui.hud.StreamerFloatingHudManager

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.core.permission.PermissionManager
import com.fairylive.app.databinding.ActivityMainBinding
import com.fairylive.app.ui.dashboard.DashboardFragment
import com.fairylive.app.ui.diagnostics.DiagnosticsFragment
import com.fairylive.app.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity(), StreamStateNotifier.Listener {

    private lateinit var binding: ActivityMainBinding
    private var hudManager: StreamerFloatingHudManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Core permissions handled on app start
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hudManager = StreamerFloatingHudManager(this)

        StreamStateNotifier.addListener(this)

        val missing = PermissionManager.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setupNavigation()
        updateHeaderStatusBadge(StreamStateNotifier.currentState)

        if (savedInstanceState == null) {
            switchFragment(DashboardFragment())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        StreamStateNotifier.removeListener(this)
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    switchFragment(DashboardFragment())
                    true
                }
                R.id.nav_studio -> {
                    switchFragment(com.fairylive.app.ui.studio.CreatorStudioFragment())
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        // Clear any back stack (such as diagnostics) when switching root tabs
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        if (fragment is com.fairylive.app.ui.studio.CreatorStudioFragment) {
            hudManager?.dismiss()
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in_subtle, R.anim.fade_out_subtle)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun navigateToSettings() {
        binding.bottomNavigation.selectedItemId = R.id.nav_settings
    }

    fun navigateToStudio() {
        binding.bottomNavigation.selectedItemId = R.id.nav_studio
    }

    fun openDiagnostics() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_bottom, R.anim.slide_out_bottom, R.anim.slide_in_bottom, R.anim.slide_out_bottom)
            .replace(R.id.fragment_container, DiagnosticsFragment())
            .addToBackStack("diagnostics")
            .commit()
    }

    private fun updateHeaderStatusBadge(state: StreamState) {
        when (state) {
            StreamState.IDLE -> {
                binding.headerStatusBadge.text = "OFFLINE"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
                hudManager?.dismiss()
            }
            StreamState.STARTING -> {
                binding.headerStatusBadge.text = "CONNECTING..."
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_primary))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
            }
            StreamState.READY -> {
                binding.headerStatusBadge.text = "READY"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_primary))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
            }
            StreamState.STREAMING -> {
                binding.headerStatusBadge.text = "● LIVE"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
                val currentFrag = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    if (currentFrag !is com.fairylive.app.ui.studio.CreatorStudioFragment) {
                        hudManager?.show()
                    } else {
                        hudManager?.dismiss()
                    }
                } else {
                    hudManager?.dismiss()
                }
            }
            StreamState.PAUSED -> {
                binding.headerStatusBadge.text = "PAUSED"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
            }
            StreamState.STOPPING -> {
                binding.headerStatusBadge.text = "STOPPING..."
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
            }
            StreamState.ERROR -> {
                binding.headerStatusBadge.text = "STREAM ERROR"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.headerStatusBadge.setBackgroundResource(R.drawable.bg_scene_chip)
            }
        }
    }

    override fun onStateChanged(newState: StreamState, previousState: StreamState) {
        runOnUiThread {
            updateHeaderStatusBadge(newState)
        }
    }
}
