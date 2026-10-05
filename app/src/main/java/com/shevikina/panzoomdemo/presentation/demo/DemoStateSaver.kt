package com.shevikina.panzoomdemo.presentation.demo

import androidx.lifecycle.SavedStateHandle
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode

/**
 * Сохраняет выбор пользователя между смертями процесса.
 *
 * Восстанавливается только то, что пользователь выставил рукам: режим, ассет и тумблеры.
 * Подготовка изображения и ошибки не сохраняются, потому что после возврата их нужно получать заново.
 */
internal object DemoStateSaver {

    fun restore(handle: SavedStateHandle): DemoState {
        val saved = DemoState()
        return saved.copy(
            viewerMode = handle.get<String>(KEY_VIEWER_MODE)
                ?.let { name -> ViewerMode.entries.firstOrNull { it.name == name } }
                ?: saved.viewerMode,
            selectedAsset = handle.get<String>(KEY_ASSET)
                ?.let { name -> TestImageAsset.entries.firstOrNull { it.name == name } }
                ?: saved.selectedAsset,
            checkedToggles = handle.get<ArrayList<String>>(KEY_TOGGLES)
                ?.mapNotNullTo(mutableSetOf()) { name ->
                    DemoToggle.entries.firstOrNull { it.name == name }
                }
                ?: saved.checkedToggles,
        )
    }

    fun save(
        handle: SavedStateHandle,
        state: DemoState,
    ) {
        handle[KEY_VIEWER_MODE] = state.viewerMode.name
        handle[KEY_ASSET] = state.selectedAsset.name
        handle[KEY_TOGGLES] = ArrayList(state.checkedToggles.map { it.name })
    }

    private const val KEY_VIEWER_MODE = "viewer_mode"
    private const val KEY_ASSET = "selected_asset"
    private const val KEY_TOGGLES = "checked_toggles"
}
