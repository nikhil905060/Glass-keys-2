package com.example.glasskeyboard

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

class GlassKeyboardService : InputMethodService() {

    private val letterRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    private val symbolRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?")
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
    private lateinit var bottomExtraRow: LinearLayout
    private val letterButtons = mutableListOf<Button>()
    private var shiftButton: Button? = null
    private var shiftOn = false
    private var symbolsMode = false
    private var clipboardMode = false
    private var wordBuffer = StringBuilder()

    private val clipHistory = mutableListOf<String>()
    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    clipHistory.remove(text)
                    clipHistory.add(0, text)
                    if (clipHistory.size > 10) clipHistory.removeAt(clipHistory.size - 1)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.input_view, null) as LinearLayout
        suggestionBar = root.findViewById(R.id.suggestion_bar)
        keysContainer = root.findViewById(R.id.keys_container)
        bottomExtraRow = root.findViewById(R.id.bottom_extra_row)

        root.findViewById<View>(R.id.clip_toggle)?.setOnClickListener {
            playHaptic(it)
            clipboardMode = !clipboardMode
            renderSuggestions()
        }
        root.findViewById<View>(R.id.top_globe)?.setOnClickListener {
            playHaptic(it)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        buildKeyboard()
        buildBottomExtraRow()
        renderSuggestions()
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- keyboard construction ----

    private fun buildKeyboard() {
        keysContainer.removeAllViews()
        letterButtons.clear()

        val rows = if (symbolsMode) symbolRows else letterRows

        rows.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(8)
            rowLayout.layoutParams = rowParams

            if (rowIndex == 1) rowLayout.setPadding(dp(16), 0, dp(16), 0)

            if (rowIndex == 2 && !symbolsMode) {
                val shift = makeSpecialKey("\u21E7", widthDp = 40) {
                    shiftOn = !shiftOn
                    updateCase()
                }
                shiftButton = shift
                rowLayout.addView(shift)
            }

            row.forEach { ch ->
                val key = makeCharKey(ch, isLetter = !symbolsMode)
                rowLayout.addView(key)
                if (!symbolsMode) letterButtons.add(key)
            }

            if (rowIndex == 2) {
                val back = makeSpecialKey("\u232B", widthDp = 40) { handleBackspace() }
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
        bottomRow.layoutParams = bottomParams

        val toggle = makeSpecialKey(if (symbolsMode) "ABC" else "123", widthDp = 52) {
            symbolsMode = !symbolsMode
            buildKeyboard()
        }
        bottomRow.addView(toggle)

        val space = makeSpecialKeyFlex("space") { commitAndTrack(" ") }
        val spaceParams = space.layoutParams as LinearLayout.LayoutParams
        spaceParams.marginStart = dp(6)
        space.layoutParams = spaceParams
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

    private fun buildBottomExtraRow() {
        bottomExtraRow.removeAllViews()

        val globe = Button(this)
        globe.text = "🌐"
        globe.textSize = 18f
        globe.background = null
        globe.layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT)
        globe.setOnClickListener {
            playHaptic(it)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        bottomExtraRow.addView(globe)

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        bottomExtraRow.addView(spacer)

        val mic = Button(this)
        mic.text = "🎤"
        mic.textSize = 18f
        mic.background = null
        mic.layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT)
        mic.setOnClickListener {
            playHaptic(it)
            // Voice input isn't wired up yet — placeholder for now.
        }
        bottomExtraRow.addView(mic)
    }

    private fun makeCharKey(ch: String, isLetter: Boolean): Button {
        val btn = Button(this)
        btn.text = ch
        btn.tag = ch
        btn.textSize = 20f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg))
            cornerRadius = dp(8).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(0, dp(46), 1f)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            val out = if (isLetter && shiftOn) ch.uppercase() else ch
            commitAndTrack(out)
            if (isLetter && shiftOn) {
                shiftOn = false
                updateCase()
            }
        }
        return btn
    }

    private fun makeSpecialKey(label: String, widthDp: Int, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.textSize = 15f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg_special))
            cornerRadius = dp(8).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(dp(widthDp), dp(46))
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
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
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg))
            cornerRadius = dp(8).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        val params = LinearLayout.LayoutParams(0, dp(46), 1f)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            onClick()
        }
        return btn
    }

    private fun playGlassEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP))
        }
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 90; start() }
        view.postDelayed({
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
        }, 130)
    }

    private fun playHaptic(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun commitAndTrack(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text == " " || text == "\n") wordBuffer.clear() else wordBuffer.append(text)
        renderSuggestions()
    }

    private fun handleBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (wordBuffer.isNotEmpty()) wordBuffer.deleteCharAt(wordBuffer.length - 1)
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
            cornerRadius = dp(8).toFloat()
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

    private fun chip(text: String, onTap: () -> Unit): Button {
        val btn = Button(this)
        btn.text = text
        btn.textSize = 14f
        btn.isAllCaps = false
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.setBackgroundColor(0)
        btn.maxLines = 1
        btn.ellipsize = android.text.TextUtils.TruncateAt.END
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        btn.layoutParams = params
        btn.setOnClickListener {
            playHaptic(it)
            onTap()
        }
        return btn
    }

    private fun divider(): View {
        val d = View(this)
        val p = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        p.topMargin = dp(8); p.bottomMargin = dp(8)
        d.layoutParams = p
        d.setBackgroundColor(ContextCompat.getColor(this, R.color.divider))
        return d
    }

    private fun renderSuggestions() {
        suggestionBar.removeAllViews()

        if (clipboardMode) {
            if (clipHistory.isEmpty()) {
                suggestionBar.addView(chip("Clipboard empty") {})
                return
            }
            clipHistory.take(4).forEachIndexed { index, text ->
                val preview = if (text.length > 18) text.take(18) + "…" else text
                suggestionBar.addView(chip(preview) {
                    currentInputConnection?.commitText(text, 1)
                    clipboardMode = false
                    renderSuggestions()
                })
                if (index < minOf(clipHistory.size, 4) - 1) suggestionBar.addView(divider())
            }
            return
        }

        val current = wordBuffer.toString()
        val sugs = getSuggestions()
        sugs.forEachIndexed { index, w ->
            val display = capitalizeLike(current, w)
            suggestionBar.addView(chip(display) {
                currentInputConnection?.deleteSurroundingText(current.length, 0)
                currentInputConnection?.commitText("$display ", 1)
                wordBuffer.clear()
                renderSuggestions()
            })
            if (index < sugs.size - 1) suggestionBar.addView(divider())
        }
    }
}
