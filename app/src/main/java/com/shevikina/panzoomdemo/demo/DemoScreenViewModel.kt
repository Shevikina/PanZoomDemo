package com.shevikina.panzoomdemo.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.models.enums.Enemies
import com.shevikina.panzoomdemo.models.enums.Weapons
import com.shevikina.panzoomdemo.models.states.DemoScreenState
import com.shevikina.panzoomdemo.models.states.DemoToggleItemState
import com.shevikina.panzoomdemo.models.states.ToggleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private const val TAG = "PanZoomDemo"

class DemoScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DemoScreenState())
    val state: StateFlow<DemoScreenState> = _state.asStateFlow()

    private val naiveTitle = application.getString(R.string.demo_naive_title)
    private val naiveSubtitle = application.getString(R.string.demo_naive_subtitle)
    private val tilingTitle = application.getString(R.string.demo_tiling_title)
    private val tilingSubtitle = application.getString(R.string.demo_tiling_subtitle)
    private val cullingTitle = application.getString(R.string.demo_culling_title)
    private val cullingSubtitle = application.getString(R.string.demo_culling_subtitle)
    private val lodTitle = application.getString(R.string.demo_lod_title)
    private val lodSubtitle = application.getString(R.string.demo_lod_subtitle)
    private val graphicsLayerTitle = application.getString(R.string.demo_graphics_layer_title)
    private val graphicsLayerSubtitle = application.getString(R.string.demo_graphics_layer_subtitle)

    val toggleItemList: StateFlow<List<DemoToggleItemState>> = _state
        .map { state ->
            listOf(
                DemoToggleItemState(
                    enabled = state.naiveToggle.enabled,
                    checked = state.naiveToggle.checked,
                    title = naiveTitle,
                    subtitle = naiveSubtitle,
                    onChange = ::onNaiveChange,
                    enemy = Enemies.START_STATE,
                    weapon = Weapons.START_STATE
                ),
                DemoToggleItemState(
                    enabled = state.tilingToggle.enabled,
                    checked = state.tilingToggle.checked,
                    title = tilingTitle,
                    subtitle = tilingSubtitle,
                    onChange = ::onTilingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                DemoToggleItemState(
                    enabled = state.cullingToggle.enabled,
                    checked = state.cullingToggle.checked,
                    title = cullingTitle,
                    subtitle = cullingSubtitle,
                    onChange = ::onCullingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                DemoToggleItemState(
                    enabled = state.lodToggle.enabled,
                    checked = state.lodToggle.checked,
                    title = lodTitle,
                    subtitle = lodSubtitle,
                    onChange = ::onLodChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.SUBSAMPLING
                ),
                DemoToggleItemState(
                    enabled = state.graphicsLayerToggle.enabled,
                    checked = state.graphicsLayerToggle.checked,
                    title = graphicsLayerTitle,
                    subtitle = graphicsLayerSubtitle,
                    onChange = ::onGraphicsLayerChange,
                    enemy = Enemies.COST_PER_FRAME,
                    weapon = Weapons.LATE_TRANSFORMATION
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private fun onNaiveChange(checked: Boolean) {
        Log.d(TAG, "Toggle Naive: $checked (остальные значения сброшены)")
        _state.update { DemoScreenState(naiveToggle = ToggleState(checked = checked)) }
    }

    private fun onTilingChange(checked: Boolean) {
        Log.d(TAG, "Toggle Tiling: $checked")
        _state.update { current ->
            if (!checked) {
                if (current.cullingToggle.checked)
                    Log.d(TAG, "Toggle Culling: checked+enabled false (сброшен вместе с Tiling)")
                if (current.lodToggle.checked)
                    Log.d(TAG, "Toggle LOD: checked+enabled false (сброшен вместе с Tiling)")
                if (current.graphicsLayerToggle.checked)
                    Log.d(
                        TAG,
                        "Toggle GraphicsLayer: checked+enabled false (сброшен вместе с Tiling)"
                    )
                current.copy(
                    tilingToggle = ToggleState(checked = false),
                    cullingToggle = ToggleState(checked = false, enabled = false),
                    lodToggle = ToggleState(checked = false, enabled = false),
                    graphicsLayerToggle = ToggleState(checked = false, enabled = false)
                )
            } else {
                Log.d(TAG, "Toggle Culling: enabled true (доступен только вместе с Tiling)")
                Log.d(TAG, "Toggle LOD: enabled true (доступен только вместе с Tiling)")
                Log.d(TAG, "Toggle GraphicsLayer: enabled true (доступен только вместе с Tiling)")
                current.copy(
                    tilingToggle = ToggleState(checked = true),
                    cullingToggle = ToggleState(
                        checked = current.cullingToggle.checked,
                        enabled = true
                    ),
                    lodToggle = ToggleState(
                        checked = current.lodToggle.checked,
                        enabled = true
                    ),
                    graphicsLayerToggle = ToggleState(
                        checked = current.graphicsLayerToggle.checked,
                        enabled = true
                    )
                )
            }
        }
    }

    private fun onCullingChange(checked: Boolean) {
        Log.d(TAG, "Toggle Culling: $checked")
        _state.update { it.copy(cullingToggle = ToggleState(checked = checked)) }
    }

    private fun onLodChange(checked: Boolean) {
        Log.d(TAG, "Toggle LOD: $checked")
        _state.update { it.copy(lodToggle = ToggleState(checked = checked)) }
    }

    private fun onGraphicsLayerChange(checked: Boolean) {
        Log.d(TAG, "Toggle GraphicsLayer: $checked")
        _state.update { it.copy(graphicsLayerToggle = it.graphicsLayerToggle.copy(checked = checked)) }
    }
}
