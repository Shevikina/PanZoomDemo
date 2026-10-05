package com.shevikina.panzoomdemo.presentation.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors

/** Достаёт [PresentationEntryPoint] из application-компонента и кеширует его в Composition. */
@Composable
internal fun rememberPresentationEntryPoint(): PresentationEntryPoint {
    val context: Context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, PresentationEntryPoint::class.java)
    }
}
