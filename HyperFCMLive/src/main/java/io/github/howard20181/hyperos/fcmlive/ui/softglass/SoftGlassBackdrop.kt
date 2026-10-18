package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.blur.LayerBackdrop

@Stable
class SoftGlassBackdropHostState {
    private val registeredBackdrops = mutableListOf<LayerBackdrop>()

    var modalBackdrop by mutableStateOf<LayerBackdrop?>(null)
        private set

    fun registerBackdrop(backdrop: LayerBackdrop) {
        registeredBackdrops += backdrop
        modalBackdrop = backdrop
    }

    fun unregisterBackdrop(backdrop: LayerBackdrop) {
        val index = registeredBackdrops.indexOfLast { it === backdrop }
        if (index >= 0) {
            registeredBackdrops.removeAt(index)
        }
        modalBackdrop = registeredBackdrops.lastOrNull()
    }
}

val LocalSoftGlassBackdropHostState =
    compositionLocalOf<SoftGlassBackdropHostState?> { null }

@Composable
fun rememberSoftGlassBackdropHostState(): SoftGlassBackdropHostState {
    return remember { SoftGlassBackdropHostState() }
}

@Composable
fun ProvideSoftGlassBackdropHost(
    content: @Composable () -> Unit
) {
    val inheritedState = LocalSoftGlassBackdropHostState.current
    val ownedState = rememberSoftGlassBackdropHostState()
    val state = inheritedState ?: ownedState
    CompositionLocalProvider(LocalSoftGlassBackdropHostState provides state) {
        content()
    }
}

/** Makes an already captured page backdrop available to sibling dialogs and sheets. */
@Composable
fun RegisterSoftGlassModalBackdrop(backdrop: LayerBackdrop?) {
    val state = LocalSoftGlassBackdropHostState.current
    DisposableEffect(state, backdrop) {
        if (state != null && backdrop != null) {
            state.registerBackdrop(backdrop)
            onDispose { state.unregisterBackdrop(backdrop) }
        } else {
            onDispose { }
        }
    }
}
