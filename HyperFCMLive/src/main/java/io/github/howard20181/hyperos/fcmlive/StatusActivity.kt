package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Whether the module can actually do anything on this ROM.
 *
 * The question this screen answers is not "is the module enabled" — it is
 * "does this ROM still have the methods my hooks are written against". That is
 * the usual reason a module looks fine and does nothing: the ROM moved on, the
 * target method is gone, and nothing anywhere says so.
 *
 * Both hosts are reachable from here, so this is a real check and not an
 * inference: PowerKeeper ships as an installed package and system_server's
 * classes ship as readable framework jars, so every target on the list is
 * actually loaded here and asked whether it is still there.
 *
 * The one thing it still does not claim is whether a hook is installed right
 * now. Hooks run in system_server and PowerKeeper and neither can report back
 * to this process, so anything promising that would be guessing — it is left to
 * the log command at the bottom, the only place it shows up.
 *
 * The GMS connection half matters for a different reason: most "push stopped
 * overnight" reports turn out to be the connection, not the module, and the
 * network type decides how long GMS waits between heartbeats. Showing it here
 * means the first thing a user checks is the thing most likely to be at fault.
 */
class StatusActivity : Activity() {

    private var content: LinearLayout? = null
    private var hookGroup: LinearLayout? = null
    private var hookSummary: TextView? = null
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Every other screen does this and this one did not, which is why switching
     * the in-app language repainted the rest of the app and left this page —
     * top bar included — on the device language. Same rewrite also carries the
     * light/dark override, so a forced dark screen used to come out light here.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(R.layout.activity_status)
        applySystemBarInsets()

        val back = findViewById<View>(R.id.btn_back)
        back?.setOnClickListener { finish() }

        content = findViewById(R.id.status_content) ?: return
        // Three sections in the same shape: a heading, then what belongs to it.
        // "Push connection" used to be the title *inside* the first card, which
        // made it read as one card's label rather than as the section sitting
        // level with "Hook targets" below it.
        content!!.addView(sectionLabel(getString(R.string.status_network_title), false))
        content!!.addView(networkCard())
        content!!.addView(hookSection())
        content!!.addView(sectionLabel(getString(R.string.status_diag_title), true))
        content!!.addView(diagnosticCard())
        probeHooks()
    }

    override fun onDestroy() {
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applySystemBarInsets() {
        // Passed as the field, not a fresh findViewById: onCreate resolves it
        // after this call, so whether the list gets its bottom padding depends on
        // when the insets are actually dispatched. Preserved as-is.
        UiUtils.applyBarInsets(this, findViewById(R.id.top_bar), content, 16)
    }

    /**
     * Current network and strict mode, because together they decide most of
     * "push stopped while the screen was off": the network sets how long GMS
     * waits between heartbeats, and strict mode decides whether the app is
     * being woken at all.
     */
    private fun networkCard(): View {
        val card = card()
        card.addView(keyValue(R.string.status_gms, describeGms()))
        card.addView(keyValue(R.string.status_network_active, describeNetwork()))
        card.addView(keyValue(R.string.status_strict_mode, describeStrictMode()))
        return card
    }

    /**
     * Strict mode as the module will apply it: the value the settings screen
     * wrote, which is the same one system_server reads back from the shared
     * prefs. Worth a row next to the network because "this app gets no push"
     * is answered by this switch about as often as by the connection — with it
     * on, an app left unchecked is meant to be left alone.
     */
    private fun describeStrictMode(): String {
        return getString(
            if (Prefs.readLocalStrictMode(this)) R.string.status_yes else R.string.status_no
        )
    }

    /** GMS is the thing being kept alive; if it is not installed, nothing below matters. */
    private fun describeGms(): String {
        return try {
            val pi = packageManager.getPackageInfo(GMS_PACKAGE, 0)
            pi.versionName ?: getString(R.string.status_unknown)
        } catch (t: Throwable) {
            getString(R.string.status_gms_absent)
        }
    }

    /** Section whose contents arrive once the probe finishes. */
    private fun hookSection(): View {
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // No longer the first section on the page: it follows the network card,
        // so it takes the extra margin like Diagnostics does.
        wrapper.addView(sectionLabel(getString(R.string.status_hooks_title), true))

        hookSummary = TextView(this).also {
            it.text = getString(R.string.status_probing)
            it.setTextSize(12f)
            it.setTextColor(getColor(R.color.md_on_surface_variant))
            it.setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        wrapper.addView(hookSummary)

        // No background of its own: each host renders as its own card, so the
        // container only stacks them.
        hookGroup = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(hookGroup)

        return wrapper
    }

    private fun probeHooks() {
        val appContext = applicationContext
        probeExecutor.execute {
            val items = HookStatus.probe(appContext)
            mainHandler.post { renderHooks(items) }
        }
    }

    private fun renderHooks(items: List<HookStatus.Item>) {
        val group = hookGroup ?: return
        val summary = hookSummary ?: return
        group.removeAllViews()
        var present = 0
        var unexpected = 0
        var unknown = 0
        for (item in items) {
            if (item.state == HookStatus.State.PRESENT) {
                present++
            }
            if (item.isUnexpectedAbsence()) {
                unexpected++
            }
            if (item.state == HookStatus.State.UNKNOWN) {
                unknown++
            }
        }
        if (unknown == items.size) {
            summary.text = getString(R.string.status_hooks_unreachable)
            return
        }
        summary.text = if (unexpected == 0) {
            getString(R.string.status_hooks_ok, present, items.size)
        } else {
            getString(
                R.string.status_hooks_missing, unexpected, present,
                items.size
            )
        }

        // One card per host. The two hosts are separate processes holding
        // separate classes, so the list is cut at the same line the module
        // itself is cut at — a single card with a divider inside read as one
        // continuous list rather than as two different pieces of software.
        var card: LinearLayout? = null
        var side: HookStatus.Side? = null
        var firstRow = true
        for (item in items) {
            if (item.side != side) {
                side = item.side
                card = hostCard(card == null)
                card.addView(groupHeader(getString(side!!.labelRes)))
                group.addView(card)
                firstRow = true
            } else if (!firstRow) {
                card!!.addView(divider())
            }
            card!!.addView(hookRow(item))
            firstRow = false
        }
    }

    private fun hookRow(item: HookStatus.Item): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52)
        row.setPadding(dp(16), dp(12), dp(16), dp(12))

        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        val textLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        textLp.marginEnd = dp(12)

        val name = TextView(this)
        name.text = item.target
        name.setTextSize(13f)
        // Method names are identifiers, not prose: monospaced they line up and
        // stay readable where a proportional face makes them run together.
        name.typeface = Typeface.MONOSPACE
        name.setTextColor(getColor(R.color.md_on_surface))
        text.addView(name)

        val note = noteFor(item)
        if (note != 0) {
            val noteView = TextView(this)
            noteView.text = getString(note)
            noteView.setTextSize(11f)
            noteView.setTextColor(getColor(R.color.md_hint_light))
            text.addView(noteView)
        }
        row.addView(text, textLp)

        val state = TextView(this)
        state.text = stateText(item)
        state.setTextSize(12f)
        state.setTextColor(stateColor(item))
        // The state words are several times longer in English than in Chinese,
        // and the names they sit next to are monospaced identifiers that are
        // long to begin with. Capping this column keeps a long state wrapping
        // inside its own space instead of squeezing the name out of the row.
        state.maxWidth = screenWidth() * 45 / 100
        state.gravity = Gravity.END
        row.addView(state)
        return row
    }

    private fun stateText(item: HookStatus.Item): String {
        return when (item.state) {
            HookStatus.State.PRESENT -> getString(R.string.status_state_present)
            HookStatus.State.ABSENT -> getString(
                if (item.isUnexpectedAbsence()) {
                    R.string.status_state_absent_unexpected
                } else {
                    R.string.status_state_absent_expected
                }
            )
            else -> getString(R.string.status_state_unknown)
        }
    }

    private fun stateColor(item: HookStatus.Item): Int {
        if (item.state == HookStatus.State.PRESENT) {
            return getColor(R.color.md_primary)
        }
        if (item.state == HookStatus.State.ABSENT) {
            // An expected absence is a ROM difference, not a problem, so it stays
            // in the quiet secondary tone; only an unexpected one gets the
            // emphasis of the accent colour.
            return getColor(
                if (item.isUnexpectedAbsence()) {
                    R.color.md_primary
                } else {
                    R.color.md_on_surface_variant
                }
            )
        }
        return getColor(R.color.md_hint_light)
    }

    /** Small line saying which ROM generation a target belongs to, if not all. */
    private fun noteFor(item: HookStatus.Item): Int {
        return when (item.expect) {
            HookStatus.Expect.HYPEROS_3 -> R.string.status_note_hyperos3
            HookStatus.Expect.HYPEROS_4 -> R.string.status_note_hyperos4
            HookStatus.Expect.NONE -> R.string.status_note_never
            HookStatus.Expect.OPTIONAL -> R.string.status_note_optional
            else -> 0
        }
    }

    private fun diagnosticCard(): View {
        val card = card()
        card.addView(commandRow(DIAG_LOG))
        card.addView(commandRow(DIAG_GCM))
        return card
    }

    private fun commandRow(command: String): View {
        val tv = TextView(this)
        tv.text = command
        tv.setTextSize(12f)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(getColor(R.color.md_on_surface))
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        tv.layoutParams = lp
        tv.setBackgroundResource(R.drawable.bg_card_press_ripple)
        tv.setOnClickListener { v ->
            UiUtils.tapFeedback(v)
            copyToClipboard(command)
        }
        return tv
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("command", text))
                Toast.makeText(this, R.string.status_copied, Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    // ---- small builders -------------------------------------------------

    private fun card(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12)
        card.layoutParams = lp
        return card
    }

    /**
     * A card holding one host's targets. A real card edge per host, with a gap
     * between them, rather than one long card split by dividers.
     */
    private fun hostCard(firstCard: Boolean): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(0, dp(8), 0, dp(8))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (!firstCard) {
            lp.topMargin = dp(14)
        }
        card.layoutParams = lp
        return card
    }

    /**
     * A section heading, so the three parts below the top bar read as sections
     * rather than as more cards. The first one keeps the top margin; anything
     * that follows gets extra space above it so it is not read as a continuation
     * of the list it sits under.
     */
    private fun sectionLabel(text: String, spaced: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(14f)
        tv.setTextColor(getColor(R.color.md_primary))
        tv.typeface = Typeface.create("sans-medium", Typeface.NORMAL)
        tv.setPadding(dp(8), if (spaced) dp(32) else dp(20), dp(8), dp(6))
        return tv
    }

    /**
     * Name of the process a group of targets belongs to. It sits inside a card
     * of its own, so it also carries the host's scope name — the same word
     * LSPosed shows in the module's scope list.
     */
    private fun groupHeader(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(12f)
        tv.typeface = Typeface.create("sans-medium", Typeface.NORMAL)
        tv.setTextColor(getColor(R.color.md_on_surface))
        tv.setPadding(dp(16), dp(10), dp(16), dp(6))
        return tv
    }

    private fun keyValue(keyRes: Int, value: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))

        val key = TextView(this)
        key.text = getString(keyRes)
        key.setTextSize(14f)
        key.setTextColor(getColor(R.color.md_on_surface_variant))
        val keyLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        keyLp.marginEnd = dp(12)

        val valView = TextView(this)
        valView.text = value
        valView.setTextSize(14f)
        valView.setTextColor(getColor(R.color.md_on_surface))
        valView.typeface = Typeface.create("sans-medium", Typeface.NORMAL)
        // Both halves share the row. A wrap_content value would claim the whole
        // width first — an English label ("Google Play services") next to a long
        // value (a full GMS version string) then leaves nothing for the label —
        // so the two split the row evenly and each wraps instead.
        val valLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        valView.gravity = Gravity.END

        row.addView(key, keyLp)
        row.addView(valView, valLp)
        return row
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(getColor(R.color.md_state_hover))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)
        )
        lp.marginStart = dp(16)
        lp.marginEnd = dp(16)
        v.layoutParams = lp
        return v
    }

    private fun describeNetwork(): String {
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
                ?: return getString(R.string.status_unknown)
            val network = cm.activeNetwork ?: return getString(R.string.status_network_none)
            val caps = cm.getNetworkCapabilities(network)
                ?: return getString(R.string.status_unknown)
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    getString(R.string.status_network_wifi)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    getString(R.string.status_network_cellular)
                else -> getString(R.string.status_network_other)
            }
        } catch (t: Throwable) {
            getString(R.string.status_unknown)
        }
    }

    private fun dp(value: Int): Int = UiUtils.dp(this, value)

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    companion object {
        private const val DIAG_LOG =
            "adb logcat -d -b all | grep -i LSPosedLogDaemon"
        private const val DIAG_GCM =
            "adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService"
        private const val GMS_PACKAGE = "com.google.android.gms"
    }
}
