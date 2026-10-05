package com.shevikina.panzoomdemo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.usecase.IRecordJankUseCase
import com.shevikina.panzoomdemo.presentation.demo.DemoScreen
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val NANOS_PER_MS = 1_000_000

/** Единственная Activity приложения. Поднимает JankStats и запускает DemoScreen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var recordJank: IRecordJankUseCase

    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Стили баров заданы явно, чтобы цвет иконок зависел от того,
        // что под ними нарисовано, а не от системной темы.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PanZoomDemoTheme {
                DemoScreen()
            }
        }
        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                DemoLog.warn("Jank: ${frameData.frameDurationUiNanos / NANOS_PER_MS}ms")
                recordJank()
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
