package com.fairylive.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fairylive.app.ui.splash.FairySplashFlowView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SplashActivity presents the seamless 9-second branded logo-drawing animation
 * for Fairy Live on a deep nocturnal violet (#130E22) backdrop.
 *
 * Driven by hardware-accelerated GPU canvas (FairySplashFlowView), running at
 * native 60/120 FPS with zero decoder black flashes, zero flicker, and fluid parametric flow.
 *
 * Tapping anywhere or clicking "ENTER STUDIO ›" transitions smoothly to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private var splashFlowView: FairySplashFlowView? = null
    private val isNavigating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        splashFlowView = findViewById(R.id.splashFlowView)
        val btnSkip = findViewById<TextView>(R.id.btnSkipSplash)
        val root = findViewById<View>(R.id.splashRoot)

        root.setOnClickListener {
            navigateToMain()
        }

        btnSkip?.setOnClickListener {
            navigateToMain()
        }

        splashFlowView?.onAnimationCompleteListener = {
            Log.i(TAG, "Splash 9s sequence completed -> smoothly transitioning to MainActivity")
            navigateToMain()
        }

        splashFlowView?.startAnimation()
    }

    private fun navigateToMain() {
        if (!isNavigating.compareAndSet(false, true)) {
            return
        }

        try {
            splashFlowView?.stopAnimation()
        } catch (_: Exception) {}

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onPause() {
        super.onPause()
        splashFlowView?.pauseAnimation()
    }

    override fun onResume() {
        super.onResume()
        splashFlowView?.resumeAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        splashFlowView?.stopAnimation()
        splashFlowView = null
    }

    companion object {
        private const val TAG = "SplashActivity"
    }
}
