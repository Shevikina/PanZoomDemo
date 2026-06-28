package com.shevikina.panzoomdemo.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.models.enums.Enemies
import com.shevikina.panzoomdemo.models.enums.Weapons
import com.shevikina.panzoomdemo.models.states.DemoScreenState
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

    val toggleList: StateFlow<List<ToggleState>> = _state
        .map { state ->
            listOf(
                ToggleState(
                    checked = state.naiveToggle,
                    title = naiveTitle,
                    subtitle = naiveSubtitle,
                    onChange = ::onNaiveChange,
                    enemy = Enemies.START_STATE,
                    weapon = Weapons.START_STATE
                ),
                ToggleState(
                    checked = state.tilingToggle,
                    title = tilingTitle,
                    subtitle = tilingSubtitle,
                    onChange = ::onTilingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                ToggleState(
                    checked = state.cullingToggle,
                    title = cullingTitle,
                    subtitle = cullingSubtitle,
                    onChange = ::onCullingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                ToggleState(
                    checked = state.lodToggle,
                    title = lodTitle,
                    subtitle = lodSubtitle,
                    onChange = ::onLodChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.SUBSAMPLING
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
        _state.update { DemoScreenState(naiveToggle = checked) }
    }

    private fun onTilingChange(checked: Boolean) {
        Log.d(TAG, "Toggle Tiling: $checked")
        _state.update { current ->
            if (!checked) {
                if (current.cullingToggle)
                    Log.d(TAG, "Toggle Culling: false (сброшен вместе с Tiling)")
                if (current.lodToggle)
                    Log.d(TAG, "Toggle LOD: false (сброшен вместе с Tiling)")
                current.copy(tilingToggle = false, cullingToggle = false, lodToggle = false)
            } else {
                current.copy(tilingToggle = true)
            }
        }
    }

    private fun onCullingChange(checked: Boolean) {
        _state.update { current ->
            if (current.tilingToggle) {
                Log.d(TAG, "Toggle Culling: $checked")
                current.copy(cullingToggle = checked)
            } else {
                Log.d(TAG, "Toggle Culling: проигнорирован (Tiling выключен)")
                current
            }
        }
    }

    private fun onLodChange(checked: Boolean) {
        _state.update { current ->
            if (current.tilingToggle) {
                Log.d(TAG, "Toggle LOD: $checked")
                current.copy(lodToggle = checked)
            } else {
                Log.d(TAG, "Toggle LOD: проигнорирован (Tiling выключен)")
                current
            }
        }
    }
}
