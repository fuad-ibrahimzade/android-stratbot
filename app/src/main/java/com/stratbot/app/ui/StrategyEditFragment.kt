package com.stratbot.app.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.stratbot.app.R
import com.stratbot.app.data.Services
import com.stratbot.app.model.Strategy
import com.stratbot.app.model.Tf

class StrategyEditFragment : Fragment() {

    companion object {
        private const val ARG_ID = "strategy_id"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        fun newInstance(id: String?) = StrategyEditFragment().apply {
            arguments = bundleOf(ARG_ID to id)
        }
    }

    private var strategy: Strategy? = null
    private lateinit var nameEt: EditText
    private lateinit var symbolEt: EditText
    private lateinit var paramsEt: EditText
    private lateinit var codeEt: EditText
    private lateinit var feeEt: EditText
    private lateinit var slipEt: EditText
    private lateinit var shortCb: CheckBox
    private lateinit var exSpin: Spinner
    private lateinit var tfSpin: Spinner

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val id = arguments?.getString(ARG_ID)
        val existing = id?.let { Services.strategies.get(it) } ?: Strategy(code = STARTER_CODE)
        strategy = existing

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(ctx, R.color.bg))
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 4))
        }
        head.addView(Ui.button(ctx, "< Back") { parentFragmentManager.popBackStack() })
        head.addView(
            Ui.txt(ctx, "Edit strategy", 16f, bold = true),
            LinearLayout.LayoutParams(MATCH, WRAP, 1f),
        )
        head.addView(Ui.button(ctx, "Save") { save() })
        root.addView(head)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }

        fun label(t: String) = body.addView(Ui.txt(ctx, t, 11f, Ui.col(ctx, R.color.textSecondary)))

        label("NAME")
        nameEt = field(ctx, existing.name)
        body.addView(nameEt)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val exs = listOf("binance", "bitfinex")
        exSpin = Spinner(ctx).apply {
            // ArrayAdapter keeps the list it is given *by reference* and clear()/addAll()
            // mutate it in place — so an adapter may only ever own a private, mutable copy.
            // Sharing the immutable listOf() (or Tf.ALL below) crashes with
            // UnsupportedOperationException when the list is refreshed.
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, ArrayList(exs))
            setSelection(exs.indexOf(existing.exchange).coerceAtLeast(0))
        }
        val tfOpts = Tf.supportedFor(existing.exchange)
        tfSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, ArrayList(tfOpts))
            setSelection(tfOpts.indexOf(existing.timeframe).coerceAtLeast(0))
        }
        row.addView(
            wrap(ctx, "Exchange", exSpin), LinearLayout.LayoutParams(0, WRAP, 1.1f)
        )
        row.addView(
            wrap(ctx, "Timeframe", tfSpin), LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        label("SYMBOL")
        symbolEt = field(ctx, existing.symbol)
        body.addView(symbolEt)

        label("PARAMS (JSON)")
        paramsEt = field(ctx, existing.paramsJson, multi = true, mono = true)
        body.addView(paramsEt, LinearLayout.LayoutParams(MATCH, WRAP))

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        feeEt = field(ctx, existing.feeBps.toString())
        slipEt = field(ctx, existing.slippageBps.toString())
        feeEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        slipEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        row2.addView(wrap(ctx, "Fees (bps)", feeEt), LinearLayout.LayoutParams(0, WRAP, 1f))
        row2.addView(wrap(ctx, "Slippage (bps)", slipEt), LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))

        shortCb = CheckBox(ctx).apply {
            text = "Allow shorting (backtest/dry only)"
            setTextColor(Ui.col(ctx, R.color.textPrimary))
            isChecked = existing.allowShort
        }
        body.addView(shortCb)

        label("JAVASCRIPT CODE")
        codeEt = EditText(ctx).apply {
            setText(existing.code)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Ui.col(ctx, R.color.textPrimary))
            gravity = Gravity.TOP
            inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            minHeight = Ui.dp(ctx, 320)
            setBackgroundResource(R.drawable.btn_pill)
        }
        body.addView(codeEt, LinearLayout.LayoutParams(MATCH, WRAP))

        val delRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        delRow.setPadding(0, Ui.dp(ctx, 14), 0, 0)
        delRow.addView(Ui.button(ctx, "Delete strategy") { confirmDelete(existing) })

        val warn = android.widget.TextView(ctx).apply {
            text = "Callbacks: init(ctx) · onBar(ctx, bar) · onTick(ctx, tick)\n" +
                "ctx: buy/sell/buyFraction/sellFraction/close · limitBuy/limitSell/cancel\n" +
                "      bars/closes/highs/lows/mid/openOrders · price/bid/ask/position/cash/equity\n" +
                "Globals: sma ema rsi atr stdev macd highest lowest slice shift len last"
            textSize = 10f
            setTextColor(Ui.col(ctx, R.color.textSecondary))
            setPadding(0, Ui.dp(ctx, 10), 0, 0)
        }
        body.addView(delRow)
        body.addView(warn)

        // timeframe re-selects when exchange changes
        exSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id2: Long) {
                // supportedFor() hands back a fresh list and the adapter owns its own
                // copy, so clear()/addAll() here touches nothing shared.
                val opts = Tf.supportedFor(exs[pos])
                @Suppress("UNCHECKED_CAST")
                (tfSpin.adapter as ArrayAdapter<String>).let {
                    it.clear(); it.addAll(opts); it.notifyDataSetChanged()
                }
                tfSpin.setSelection(opts.indexOf(existing.timeframe).coerceAtLeast(0))
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun field(
        ctx: android.content.Context, value: String, multi: Boolean = false, mono: Boolean = false
    ) = EditText(ctx).apply {
        setText(value)
        isSingleLine = !multi
        setTextColor(Ui.col(ctx, R.color.textPrimary))
        setHintTextColor(Ui.col(ctx, R.color.textSecondary))
        if (mono) typeface = Typeface.MONOSPACE
        if (multi) {
            inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            gravity = Gravity.TOP
            minHeight = Ui.dp(ctx, 72)
        }
        setBackgroundResource(R.drawable.btn_pill)
        setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
    }

    private fun wrap(ctx: android.content.Context, label: String, v: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(Ui.txt(ctx, label, 11f, Ui.col(ctx, R.color.textSecondary)))
            addView(v)
        }

    private fun save() {
        val s = strategy ?: return
        val updated = s.copy(
            name = nameEt.text.toString().trim().ifEmpty { s.name },
            symbol = symbolEt.text.toString().trim().uppercase().ifEmpty { s.symbol },
            exchange = (exSpin.selectedItem as? String) ?: s.exchange,
            timeframe = (tfSpin.selectedItem as? String) ?: s.timeframe,
            paramsJson = paramsEt.text.toString().trim().ifEmpty { "{}" },
            code = codeEt.text.toString(),
            feeBps = feeEt.text.toString().toDoubleOrNull() ?: s.feeBps,
            slippageBps = slipEt.text.toString().toDoubleOrNull() ?: s.slippageBps,
            allowShort = shortCb.isChecked,
        )
        Services.strategies.save(updated)
        Ui.toast(requireContext(), "Saved “${updated.name}”")
        parentFragmentManager.popBackStack()
    }

    private fun confirmDelete(s: Strategy) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete strategy?")
            .setMessage("“${s.name}” will be removed. Existing run reports stay.")
            .setPositiveButton("Delete") { _, _ ->
                Services.strategies.delete(s.id)
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}