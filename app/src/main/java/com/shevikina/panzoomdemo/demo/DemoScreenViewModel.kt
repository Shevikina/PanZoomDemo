package com.shevikina.panzoomdemo.demo

import android.app.Application
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
                    enable = state.naiveToggle,
                    title = naiveTitle,
                    subtitle = naiveSubtitle,
                    onChange = ::onNaiveChange,
                    enemy = Enemies.START_STATE,
                    weapon = Weapons.START_STATE
                ),
                ToggleState(
                    enable = state.tilingToggle,
                    title = tilingTitle,
                    subtitle = tilingSubtitle,
                    onChange = ::onTilingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                ToggleState(
                    enable = state.cullingToggle,
                    title = cullingTitle,
                    subtitle = cullingSubtitle,
                    onChange = ::onCullingChange,
                    enemy = Enemies.MEMORY,
                    weapon = Weapons.REGION_DECODE
                ),
                ToggleState(
                    enable = state.lodToggle,
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

    private fun onNaiveChange(enable: Boolean) {
        _state.update { it.copy(naiveToggle = enable) }
    }

    private fun onTilingChange(enable: Boolean) {
        _state.update { current ->
            if (!enable) current.copy(tilingToggle = false, cullingToggle = false)
            else current.copy(tilingToggle = true)
        }
    }

    private fun onCullingChange(enable: Boolean) {
        _state.update { current ->
            if (current.tilingToggle) current.copy(cullingToggle = enable) else current
        }
    }

    private fun onLodChange(enable: Boolean) {
        _state.update { it.copy(lodToggle = enable) }
    }
}
