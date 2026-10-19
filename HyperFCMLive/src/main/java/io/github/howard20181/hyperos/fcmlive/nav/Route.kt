package io.github.howard20181.hyperos.fcmlive.nav

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * One page of the app, as a key in the single back stack [NavDisplay] renders.
 *
 * The sub-pages used to be their own Activities. A covered Activity is *stopped*:
 * its frame clock pauses, its surface is handed to the system, and coming back to
 * it means building a first frame from nothing — which is what the jank after a
 * back gesture's release was made of, and why the reveal showed a photograph of
 * the page instead of the page. In one stack every layer below the top stays
 * composed, laid out and drawn, so both the gesture and the settle after it move
 * layers that already exist.
 *
 * `@Serializable` is a hard requirement of `rememberNavBackStack`, which persists
 * the stack by serializing it; the members are `data object`s so the content key
 * derived from each one — and with it the entry's saveable state — is value-stable
 * across process death.
 */
@Serializable
sealed interface Route : NavKey {

    /** The home: the three tabs, in whichever shell the current look wears. */
    @Serializable
    data object Main : Route

    /**
     * The about page the home's 「关于」 row opens: the hero and the project rows,
     * dressed by whichever look is current.
     */
    @Serializable
    data object About : Route

    /** The licence list, opened from a settings page's 「开源许可」 row. */
    @Serializable
    data object Licenses : Route

    /** The experiment switches, opened from a settings page's 「实验性功能」 row. */
    @Serializable
    data object Experiment : Route
}
