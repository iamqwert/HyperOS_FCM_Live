package io.github.howard20181.hyperos.fcmlive

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.theme.applyPalette
import io.github.howard20181.hyperos.fcmlive.UiUtils.rowClick

/**
 * Experiment switches.
 *
 * Everything here is off by default and touches third-party apps the module
 * otherwise never interacts with, so the switches live one screen deeper than
 * the About entry instead of on the main page. Each row is self-contained:
 * the switch is the authority, the row tap just flips it, and the value label
 * states the effect — the same shape as the About appearance rows.
 */
class ExperimentActivity : AppCompatActivity() {

    private val tips = TooltipHost(this)
    private var shieldSwitch: MaterialSwitch? = null
    private var shieldState: TextView? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(R.layout.activity_experiment)
        applySystemBarInsets()

        val back = findViewById<View>(R.id.btn_back)
        back?.setOnClickListener { finish() }
        tips.attach(back, R.string.back)

        bindWechatShieldRow()
    }

    override fun onDestroy() {
        tips.dismiss()
        super.onDestroy()
    }

    /**
     * WeChat battery shield. The switch writes through [Prefs.writeWechatShield]
     * (remote group + broadcast), so the PowerKeeper hook sees the change the
     * next time a qualifying write arrives — no reload, no reboot. The value
     * label always mirrors the switch; there is no separate listener on it.
     */
    private fun bindWechatShieldRow() {
        shieldSwitch = findViewById(R.id.wechat_shield_switch)
        shieldState = findViewById(R.id.wechat_shield_state)
        val row = findViewById<View>(R.id.row_wechat_shield)
        val switchView = shieldSwitch ?: return
        switchView.applyPalette(ThemeEngine.palette(this))
        val enabled = Prefs.readLocalWechatShield(this)
        row?.setOnClickListener(rowClick {
            switchView.isChecked = !switchView.isChecked
            switchView.jumpDrawablesToCurrentState()
        })
        // Restore before the listener attaches, so no spurious write fires.
        switchView.isChecked = enabled
        switchView.jumpDrawablesToCurrentState()
        switchView.setOnCheckedChangeListener { _, checked ->
            Prefs.writeWechatShield(this, Prefs.remote(), checked)
            refreshWechatShieldState(checked)
        }
        refreshWechatShieldState(enabled)
    }

    private fun refreshWechatShieldState(enabled: Boolean) {
        // The off state carries no label by design: the description below the
        // switch already explains the default (untouched) behavior.
        val state = shieldState ?: return
        if (enabled) {
            state.setText(R.string.experiment_wechat_shield_on)
            state.visibility = View.VISIBLE
        } else {
            state.visibility = View.GONE
        }
    }

    /** Same edge-to-edge treatment as the About screen. */
    private fun applySystemBarInsets() {
        UiUtils.applyBarInsets(
            this, findViewById(R.id.top_bar),
            findViewById(R.id.experiment_content), 16, null
        )
    }
}
