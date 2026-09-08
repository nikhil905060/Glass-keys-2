package com.example.glasskeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.text = "Glass Keyboard"
        title.textSize = 22f
        root.addView(title)

        val info = TextView(this)
        info.text = "Step 1: enable this keyboard in system settings.\n" +
                "Step 2: switch to it from any text field.\n\n" +
                "Tip: tap the globe/keyboard icon on your system keyboard, or long-press the space bar, to switch input methods."
        info.setPadding(0, 32, 0, 48)
        root.addView(info)

        val enableBtn = Button(this)
        enableBtn.text = "Open keyboard settings"
        enableBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        root.addView(enableBtn)

        val switchBtn = Button(this)
        switchBtn.text = "Choose input method"
        switchBtn.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        root.addView(switchBtn)

        setContentView(root)
    }
}
