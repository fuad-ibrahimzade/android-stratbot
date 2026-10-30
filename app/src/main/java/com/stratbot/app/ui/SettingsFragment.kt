package com.stratbot.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stratbot.app.R
import com.stratbot.app.data.Services
import com.stratbot.app.runner.RunManager

class SettingsFragment : Fragment() {

    private val fields = mutableMapOf<String, EditText>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val s = Services.settings

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(ctx, R.color.bg))
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 32))
        }
        body.addView(Ui.txt(ctx, "Settings", 18f, bold = true).apply { setPadding(0, 0, 0, Ui.dp(ctx, 4)) })

        // ---- API keys
        body.addView(Ui.section(ctx, "api keys"))
        val kp = Services.keys

        val binMode = CheckBox(ctx).apply {
            text = "Use testnet.binance.vision (spot test keys)"
            setTextColor(Ui.col(ctx, R.color.textPrimary))
            isChecked = s.testnet
        }
        body.addView(binMode)

        inp(body, "binance_api_key", "Binance API key", kp.key("binance"), password = true)
        inp(body, "binance_api_secret", "Binance API secret", kp.secret("binance"), password = true)
        inp(body, "bitfinex_api_key", "Bitfinex API key", kp.key("bitfinex"), password = true)
        inp(body, "bitfinex_api_secret", "Bitfinex API secret", kp.secret("bitfinex"), password = true)

        body.addView(
            Ui.txt(
                ctx,
                "Keys are stored in Android EncryptedSharedPreferences. " +
                    "Create them on the exchange site with trade/read permissions (no withdrawal). " +
                    "Restrict them by API IP if the exchange allows it.",
                11f, Ui.col(ctx, R.color.textSecondary)
            )
        )

        // ---- hosts
        body.addView(Ui.section(ctx, "hosts (override when the default is filtered)"))
        inp(body, "binance_market_base", "Binance market data base", s.binanceMarketBase)
        inp(body, "binance_trade_base", "Binance trade/rest base", s.binanceTradeBase)
        inp(body, "binance_ws", "Binance stream URL", s.binanceWs)
        inp(body, "bitfinex_public", "Bitfinex public base", s.bitfinexPublicBase)
        inp(body, "bitfinex_auth", "Bitfinex auth base", s.bitfinexAuthBase)
        inp(body, "bitfinex_ws", "Bitfinex WS URL", s.bitfinexWs)

        val fallback = CheckBox(ctx).apply {
            text = "REST polling fallback when the WS is silent"
            setTextColor(Ui.col(ctx, R.color.textPrimary))
            isChecked = s.restFallback
        }
        body.addView(fallback)

        // ---- defaults
        body.addView(Ui.section(ctx, "defaults"))
        inp(body, "warmup_bars", "Warmup bars (live/dry)", s.warmupBars.toString())
        inp(body, "poll_ms", "Poll interval ms (REST fallback)", s.pollIntervalMs.toString())
        inp(body, "start_cash", "Starting cash (backtest/dry)", s.startCash.toString())

        // ---- actions
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(ctx, 18), 0, 0)
        }
        actions.addView(Ui.button(ctx, "Save settings") { save(binMode, fallback) })
        actions.addView(
            Ui.button(ctx, "Stop all runs") {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Stop all runs?")
                    .setMessage("Stops every dry/live session and cancels its open orders (if strategy flag set).")
                    .setPositiveButton("Stop") { _, _ -> RunManager.stopAll(); Ui.toast(ctx, "Stopped") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        body.addView(actions)

        body.addView(
            Ui.txt(
                ctx,
                "StratBot · JS strategies running as backtest, dry-run or live-run. " +
                    "Reports are saved on disk and survive restarts.",
                11f, Ui.col(ctx, R.color.textSecondary)
            ).apply { setPadding(0, Ui.dp(ctx, 16), 0, 0) }
        )

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun inp(parent: LinearLayout, key: String, label: String, value: String, password: Boolean = false) {
        val ctx = parent.context
        parent.addView(Ui.txt(ctx, label, 11f, Ui.col(ctx, R.color.textSecondary)))
        val et = EditText(ctx).apply {
            setText(value)
            isSingleLine = true
            setTextColor(Ui.col(ctx, R.color.textPrimary))
            typeface = if (password) Typeface.MONOSPACE else typeface
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.btn_pill)
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        }
        fields[key] = et
        parent.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun save(binMode: CheckBox, fallback: CheckBox) {
        val v = { k: String -> fields[k]?.text.toString().trim() ?: "" }
        val kp = Services.keys
        kp.save("binance", v("binance_api_key"), v("binance_api_secret"))
        kp.save("bitfinex", v("bitfinex_api_key"), v("bitfinex_api_secret"))

        val s = Services.settings
        s.testnet = binMode.isChecked
        s.binanceMarketBase = v("binance_market_base").ifEmpty { s.binanceMarketBase }
        s.binanceTradeBase = v("binance_trade_base").ifEmpty { s.binanceTradeBase }
        s.binanceWs = v("binance_ws").ifEmpty { s.binanceWs }
        s.bitfinexPublicBase = v("bitfinex_public").ifEmpty { s.bitfinexPublicBase }
        s.bitfinexAuthBase = v("bitfinex_auth").ifEmpty { s.bitfinexAuthBase }
        s.bitfinexWs = v("bitfinex_ws").ifEmpty { s.bitfinexWs }
        s.restFallback = fallback.isChecked
        s.warmupBars = v("warmup_bars").toIntOrNull() ?: s.warmupBars
        s.pollIntervalMs = v("poll_ms").toLongOrNull() ?: s.pollIntervalMs
        s.startCash = v("start_cash").toDoubleOrNull() ?: s.startCash
        Ui.toast(requireContext(), "Settings saved")
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}