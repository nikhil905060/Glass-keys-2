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
