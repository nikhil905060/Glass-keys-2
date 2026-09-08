package com.example.glasskeyboard

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

class GlassKeyboardService : InputMethodService() {

    private val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    private val dictionary = listOf(
        "the", "that", "there", "then", "they", "this", "think", "time", "today", "tomorrow",
        "hello", "how", "have", "here", "help", "happy", "home", "hope",
        "and", "are", "about", "after", "also", "always", "again",
        "you", "your", "yes", "yesterday",
        "we", "well", "what", "when", "where", "why", "who", "will", "with", "would", "was", "went",
        "is", "it", "if", "in", "into",
        "for", "from", "friend",
        "good", "going", "got", "get", "give",
        "can", "come", "could", "call",
        "love", "like", "look", "little", "let",
        "need", "now", "new", "no", "not", "never",
        "okay", "of", "on", "one", "our", "out", "over",
        "please", "people", "put",
        "really", "right",
        "see", "some", "so", "sorry", "send", "soon",
        "to", "too", "tell", "thanks", "thank",
        "want", "week", "work", "world",
        "know", "just", "make", "many", "more", "much", "most", "must"
    )

    private lateinit var suggestionBar: LinearLayout
    private lateinit var keysContainer: LinearLayout
    private val letterButtons = mutableListOf<Button>()
    private var shiftButton: Button? = null
    private var shiftOn = false
    private var wordBuffer = StringBuilder()

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.input_view, null) as LinearLayout
        suggestionBar = root.findViewById(R.id.suggestion_bar)
        keysContainer = root.findViewById(R.id.keys_container)
        buildKeyboard()
        renderSuggestions()
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildKeyboard() {
        keysContainer.removeAllViews()
        letterButtons.clear()

        rows.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(6)
            rowLayout.layoutParams = rowParams
            rowLayout.gravity = View.TEXT_ALIGNMENT_CENTER

            if (rowIndex == 1) {
                rowLayout.setPadding(dp(16), 0, dp(16), 0)
            }

            if (rowIndex == 2) {
                val shift = makeSpecialKey("\u21E7", widthDp = 40) {
                    shiftOn = !shiftOn
                    updateCase()
                }
                shiftButton = shift
                rowLayout.addView(shift)
            }

            row.forEach { ch ->
                val key = makeLetterKey(ch)
                rowLayout.addView(key)
                letterButtons.add(key)
            }

            if (rowIndex == 2) {
                val back = makeSpecialKey("\u232B", widthDp = 40) {
                    handleBackspace()
                }
                rowLayout.addView(back)
            }

            keysContainer.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this)
        bottomRow.orientation = LinearLayout.HORIZONTAL
        val bottomParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        bottomParams.topMargin = dp(2)
        bottomRow.layoutParams = bottomParams

        val space = makeSpecialKeyFlex("space") {
            commitAndTrack(" ")
        }
        bottomRow.addView(space)

        val enter = makeSpecialKey("\u21B5", widthDp = 64) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            wordBuffer.clear()
            renderSuggestions()
        }
        val enterParams = enter.layoutParams as LinearLayout.LayoutParams
        enterParams.marginStart = dp(6)
        enter.layoutParams = enterParams
        bottomRow.addView(enter)

        keysContainer.addView(bottomRow)
    }

    private fun makeLetterKey(ch: String): Button {
        val btn = Button(this)
        btn.text = ch
        btn.tag = ch
        btn.textSize = 15f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg))
            cornerRadius = dp(6).toFloat()
        }
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(0, dp(46), 1f)
        params.marginEnd = dp(4)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            val out = if (shiftOn) ch.uppercase() else ch
            commitAndTrack(out)
            if (shiftOn) {
                shiftOn = false
                updateCase()
            }
        }
        return btn
    }

    private fun makeSpecialKey(label: String, widthDp: Int, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.textSize = 16f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg_special))
            cornerRadius = dp(6).toFloat()
        }
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(dp(widthDp), dp(46))
        params.marginEnd = dp(4)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            onClick()
        }
        return btn
    }

    private fun makeSpecialKeyFlex(label: String, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.textSize = 15f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg_special))
            cornerRadius = dp(6).toFloat()
        }
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(0, dp(46), 1f)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            onClick()
        }
        return btn
    }

    private fun playGlassEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP)
            )
        }
        val overlay = ContextCompat.getColor(this, R.color.glass_overlay)

        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()

        val animator = ValueAnimator.ofArgb(overlay, overlay)
        animator.duration = 90
        animator.start()

        view.postDelayed({
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(null)
            }
        }, 130)
    }

    private fun commitAndTrack(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text == " " || text == "\n") {
            wordBuffer.clear()
        } else {
            wordBuffer.append(text)
        }
        renderSuggestions()
    }

    private fun handleBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (wordBuffer.isNotEmpty()) {
            wordBuffer.deleteCharAt(wordBuffer.length - 1)
        }
        renderSuggestions()
    }

    private fun updateCase() {
        letterButtons.forEach { btn ->
            val ch = btn.tag as String
            btn.text = if (shiftOn) ch.uppercase() else ch
        }
        shiftButton?.background = GradientDrawable().apply {
            setColor(
                ContextCompat.getColor(
                    this@GlassKeyboardService,
                    if (shiftOn) R.color.key_bg_special_active else R.color.key_bg_special
                )
            )
            cornerRadius = dp(6).toFloat()
        }
    }

    private fun getSuggestions(): List<String> {
        val w = wordBuffer.toString().lowercase()
        if (w.isEmpty()) return listOf("I", "the", "you")
        val matches = dictionary.filter { it.startsWith(w) && it != w }
        return (listOf(w) + matches).distinct().take(3)
    }

    private fun capitalizeLike(source: String, word: String): String {
        if (source.isNotEmpty() && source[0].isUpperCase()) {
            return word.replaceFirstChar { it.uppercase() }
        }
        return word
    }

    private fun renderSuggestions() {
        suggestionBar.removeAllViews()
        val current = wordBuffer.toString()
        val sugs = getSuggestions()
        sugs.forEachIndexed { index, w ->
            val display = capitalizeLike(current, w)
            val btn = Button(this)
            btn.text = display
            btn.textSize = 15f
            btn.isAllCaps = false
            btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
            btn.setBackgroundColor(0)
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            btn.layoutParams = params
            btn.setOnClickListener {
                playGlassEffect(btn)
                currentInputConnection?.deleteSurroundingText(current.length, 0)
                currentInputConnection?.commitText("$display ", 1)
                wordBuffer.clear()
                renderSuggestions()
            }
            suggestionBar.addView(btn)

            if (index < sugs.size - 1) {
                val divider = View(this)
                val dividerParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                dividerParams.topMargin = dp(8)
                dividerParams.bottomMargin = dp(8)
                divider.layoutParams = dividerParams
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider))
                suggestionBar.addView(divider)
            }
        }
    }
}
