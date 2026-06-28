package com.shevikina.panzoomdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shevikina.panzoomdemo.demo.DemoScreen
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanZoomDemoTheme {
                DemoScreen()
            }
        }
    }
}