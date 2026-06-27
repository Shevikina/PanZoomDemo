package com.shevikina.panzoomdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.shevikina.panzoomdemo.demo.NaiveImageViewer
import com.shevikina.panzoomdemo.metrics.MetricsOverlay
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanZoomDemoTheme {
                Box(Modifier.fillMaxSize()) {
                    NaiveImageViewer(assetName = "test_image_medium.jpg")
                    MetricsOverlay()
                }
            }
        }
    }
}