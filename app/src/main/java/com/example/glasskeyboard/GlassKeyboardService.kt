package com.example.glasskeyboard

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

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
    private var commandButton: ImageButton? = null
    private var shiftOn = false
    private var symbolsMode = false
    private var clipboardMode = false
    private var wordBuffer = StringBuilder()

    private val clipHistory = mutableListOf<String>()
    private lateinit var clipboardManager: ClipboardManager

    private var spaceDragStartX = 0f
    private var spaceIsDragging = false

    private var previewPopup: PopupWindow? = null
    private var previewText: TextView? = null

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

        setupPreviewPopup(root)
        buildKeyboard()
        buildBottomExtraRow()
        renderSuggestions()
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupPreviewPopup(root: View) {
        previewText = TextView(this).apply {
            textSize = 22f
            setTextColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_text))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = ContextCompat.getDrawable(this@GlassKeyboardService, R.drawable.popup_bg)
            elevation = dp(6).toFloat()
        }
        previewPopup = PopupWindow(
            previewText,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isTouchable = false
            isClippingEnabled = false
        }
    }

    private fun showKeyPreview(anchor: View, text: String) {
        val popup = previewPopup ?: return
        previewText?.text = text
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - dp(20)
        val y = loc[1] - dp(48)
        try {
            if (popup.isShowing) {
                popup.update(x, y, -1, -1)
            } else {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) { }
    }

    private fun hideKeyPreview() {
        previewPopup?.takeIf { it.isShowing }?.dismiss()
    }

    private fun buildKeyboard() {
        keysContainer.removeAllViews()
        letterButtons.clear()

        val rows = if (symbolsMode) symbolRows else letterRows
        val keySize = dp(36)

        rows.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER_HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(5)
            rowLayout.layoutParams = rowParams

            if (rowIndex == 2 && !symbolsMode) {
                val cmd = makeIconKey(R.drawable.ic_command, widthDp = 38, heightPx = keySize) {
                    shiftOn = !shiftOn
                    updateCase()
                }
                commandButton = cmd
                rowLayout.addView(cmd)
            }

            row.forEach { ch ->
                val key = makeCharKey(ch, isLetter = !symbolsMode, sizePx = keySize)
                rowLayout.addView(key)
                if (!symbolsMode) letterButtons.add(key)
            }

            if (rowIndex == 2) {
                val back = makeIconKey(R.drawable.ic_backspace, widthDp = 38, heightPx = keySize) {
                    handleBackspace()
                }
                rowLayout.addView(back)
            }

            keysContainer.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this)
        bottomRow.orientation = LinearLayout.HORIZONTAL
        bottomRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val toggle = makeSpecialKey(if (symbolsMode) "ABC" else "123", widthDp = 46, heightPx = keySize) {
            symbolsMode = !symbolsMode
            buildKeyboard()
        }
        bottomRow.addView(toggle)

        val space = makeSpaceKey(keySize)
        (space.layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        bottomRow.addView(space)

        val enter = makeEnterKey(keySize)
        (enter.layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        bottomRow.addView(enter)

        keysContainer.addView(bottomRow)
    }

    private fun buildBottomExtraRow() {
        bottomExtraRow.removeAllViews()

        val globe = ImageButton(this).apply {
            setImageResource(R.drawable.ic_globe)
            background = null
            isLongClickable = false
            tooltipText = null
            layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                playHaptic(it)
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        bottomExtraRow.addView(globe)

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        bottomExtraRow.addView(spacer)

        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = null
            isLongClickable = false
            tooltipText = null
            layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                playHaptic(it)
            }
        }
        bottomExtraRow.addView(mic)
    }

    private fun makeCharKey(ch: String, isLetter: Boolean, sizePx: Int): Button {
        val btn = Button(this)
        btn.text = if (isLetter && shiftOn) ch.uppercase() else ch
        btn.tag = ch
        btn.textSize = 18f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg))
            cornerRadius = dp(12).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        btn.setPadding(0, 0, 0, 0)
        val params = LinearLayout.LayoutParams(sizePx, sizePx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> showKeyPreview(v, if (isLetter && shiftOn) ch.uppercase() else ch)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hideKeyPreview()
            }
            false
        }
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

    private fun makeSpecialKey(label: String, widthDp: Int, heightPx: Int, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.textSize = 13f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg_special))
            cornerRadius = dp(12).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        val params = LinearLayout.LayoutParams(dp(widthDp), heightPx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            onClick()
        }
        return btn
    }

    private fun makeIconKey(iconRes: Int, widthDp: Int, heightPx: Int, onClick: () -> Unit): ImageButton {
        val btn = ImageButton(this)
        btn.setImageResource(iconRes)
        btn.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        btn.setPadding(dp(8), dp(8), dp(8), dp(8))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg_special))
            cornerRadius = dp(12).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        val params = LinearLayout.LayoutParams(dp(widthDp), heightPx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            onClick()
        }
        return btn
    }

    private fun makeSpaceKey(heightPx: Int): Button {
        val btn = Button(this)
        btn.text = "space"
        btn.textSize = 13f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.key_bg))
            cornerRadius = dp(12).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        btn.layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f)

        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            commitAndTrack(" ")
        }

        val dragStepPx = dp(8)
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    spaceDragStartX = event.rawX
                    spaceIsDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - spaceDragStartX
                    if (abs(dx) > dragStepPx) {
                        spaceIsDragging = true
                        playHaptic(v)
                        sendDownUpKeyEvents(
                            if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        )
                        spaceDragStartX = event.rawX
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!spaceIsDragging) v.performClick()
                    spaceIsDragging = false
                }
            }
            true
        }
        return btn
    }

    private fun makeEnterKey(heightPx: Int): Button {
        val btn = Button(this)
        btn.text = ""
        btn.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@GlassKeyboardService, R.color.accent_blue))
            cornerRadius = dp(12).toFloat()
        }
        btn.elevation = dp(1).toFloat()
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        btn.layoutParams = LinearLayout.LayoutParams(dp(56), heightPx)
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            wordBuffer.clear()
            renderSuggestions()
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
        commandButton?.background = GradientDrawable().apply {
            setColor(
                ContextCompat.getColor(
                    this@GlassKeyboardService,
                    if (shiftOn) R.color.key_bg_special_active else R.color.key_bg_special
                )
            )
            cornerRadius = dp(12).toFloat()
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
        btn.textSize = 13f
        btn.isAllCaps = false
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.setBackgroundColor(0)
        btn.maxLines = 1
        btn.isLongClickable = false
        btn.tooltipText = null
        btn.setOnLongClickListener { true }
        btn.ellipsize = android.text.TextUtils.TruncateAt.END
        btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        btn.setOnClickListener {
            playHaptic(it)
            onTap()
        }
        return btn
    }

    private fun divider(): View {
        val d = View(this)
        val p = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        p.topMargin = dp(6); p.bottomMargin = dp(6)
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
   
