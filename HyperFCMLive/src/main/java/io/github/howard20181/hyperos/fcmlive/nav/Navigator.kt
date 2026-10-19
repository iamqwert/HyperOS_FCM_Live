package io.github.howard20181.hyperos.fcmlive.nav

import androidx.compose.runtime.Stable
import top.yukonga.miuix.kmp.nav.core.NavBackStack

/**
 * The page stack the app is read through — a thin helper over the library's
 * [NavBackStack], which is itself a `SnapshotStateList` and therefore already
 * observable: mutating it is what re-renders the [NavDisplay].
 */
@Stable
class Navigator(val backStack: NavBackStack) {

    /**
     * Pushes [route] idempotently: every route here is a `data object`, so a
     * second push of the same one is the *same* content key, which the runtime
     * rejects as a duplicate — and a double tap on a row would otherwise be one.
     * A route that needs several live instances would carry a value instead.
     */
    fun push(route: Route) {
        if (route !in backStack) backStack.add(route)
    }

    /** Pops the top page, never the home underneath it. */
    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    /**
     * Returns to the home, dropping whatever page was open on top of it. A
     * launcher shortcut relaunch goes through here: it names a tab, which is only
     * reachable from the home, so a sub-page left over from last time has to go.
     */
    fun popToMain() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
