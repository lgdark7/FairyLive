package com.fairylive.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fairylive.app.core.lifecycle.StreamState
import com.fairylive.app.core.lifecycle.StreamStateNotifier
import com.fairylive.app.databinding.ActivityMainBinding
import com.fairylive.app.ui.dashboard.DashboardFragment
import com.fairylive.app.ui.diagnostics.DiagnosticsFragment
import com.fairylive.app.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity(), StreamStateNotifier.Listener {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StreamStateNotifier.addListener(this)

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
                R.id.nav_settings -> {
                    switchFragment(SettingsFragment())
                    true
                }
                R.id.nav_diagnostics -> {
                    switchFragment(DiagnosticsFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun updateHeaderStatusBadge(state: StreamState) {
        when (state) {
            StreamState.IDLE -> {
                binding.headerStatusBadge.text = getString(R.string.status_idle)
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            }
            StreamState.READY -> {
                binding.headerStatusBadge.text = "SERVICE ACTIVE"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.fairy_cyan))
            }
            StreamState.STREAMING -> {
                binding.headerStatusBadge.text = getString(R.string.status_streaming)
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
            StreamState.PAUSED -> {
                binding.headerStatusBadge.text = "PAUSED"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            }
            StreamState.ERROR -> {
                binding.headerStatusBadge.text = "ERROR"
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
            else -> {
                binding.headerStatusBadge.text = state.name
                binding.headerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            }
        }
    }

    override fun onStateChanged(newState: StreamState, previousState: StreamState) {
        runOnUiThread {
            updateHeaderStatusBadge(newState)
        }
    }
}
