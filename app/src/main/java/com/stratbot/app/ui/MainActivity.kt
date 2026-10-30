package com.stratbot.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.stratbot.app.R

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_RUNS = "openRuns"
    }

    private var contentId = 0
    private lateinit var tabViews: List<TextView>
    private var selected = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(this@MainActivity, R.color.bg))
            weightSum = 1f
        }

        // top title bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
            setBackgroundColor(Ui.col(this@MainActivity, R.color.bg))
        }
        top.addView(
            Ui.txt(this, "StratBot", 20f, Ui.col(this, R.color.textPrimary), bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            Ui.txt(this, "JS backtest · dry · live", 11f, Ui.col(this, R.color.textSecondary)),
        )

        contentId = R.id.container
        val content = FrameLayout(this).apply { id = contentId }
        val contentLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        val bottom = buildBottomBar()

        root.addView(top)
        root.addView(content, contentLp)
        root.addView(bottom)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
                else finish()
            }
        })

        val openRuns = intent?.getBooleanExtra(EXTRA_OPEN_RUNS, false) ?: false
        selectTab(if (openRuns) 1 else savedInstanceState?.getInt("tab", 0) ?: 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_RUNS, false) == true) selectTab(1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", selected)
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.col(this@MainActivity, R.color.surface))
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 10))
        }
        val labels = listOf("Strategies", "Runs", "Settings")
        tabViews = labels.mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8))
                setOnClickListener { selectTab(i) }
                bar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        updateTabs()
        return bar
    }

    private fun updateTabs() {
        tabViews.forEachIndexed { i, v ->
            if (i == selected) {
                v.setTextColor(Ui.col(this, R.color.primary))
                v.setTypeface(v.typeface, Typeface.BOLD)
            } else {
                v.setTextColor(Ui.col(this, R.color.textSecondary))
                v.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    fun selectTab(index: Int) {
        selected = index.coerceIn(0, 2)
        updateTabs()
        val f: Fragment = when (selected) {
            0 -> StrategiesFragment()
            1 -> RunsFragment()
            else -> SettingsFragment()
        }
        clearStack()
        supportFragmentManager.beginTransaction().replace(contentId, f).commit()
    }

    /** detail screen on top of the current tab */
    fun push(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(contentId, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun clearStack() {
        while (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStackImmediate()
    }
}