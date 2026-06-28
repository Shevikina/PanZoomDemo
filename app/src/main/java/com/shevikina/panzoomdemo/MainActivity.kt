package com.shevikina.panzoomdemo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import com.shevikina.panzoomdemo.demo.DemoScreen
import com.shevikina.panzoomdemo.metrics.JankTracker
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

private const val TAG = "PanZoomDemo"
private const val NANOS_PER_MS = 1_000_000

class MainActivity : ComponentActivity() {

    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanZoomDemoTheme {
                DemoScreen()
            }
        }
        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(TAG, "Jank: ${frameData.frameDurationUiNanos / NANOS_PER_MS}ms")
                JankTracker.recordJank()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        jankStats.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jankStats.isTrackingEnabled = false
    }
}