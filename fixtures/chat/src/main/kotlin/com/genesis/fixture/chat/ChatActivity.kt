package com.genesis.fixture.chat

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Test chat screen. Intent extras switch on the failure cases the engine must survive:
 * - "variant": the input and send button get different resource ids (a UI update);
 * - "ignoreTaps": the send button ignores that many taps (unresponsive button);
 * - "invisible": sending works but no bubble appears (unconfirmable outcome).
 * A visible counter (id sent_count) shows how many messages were really sent.
 */
class ChatActivity : Activity() {

    private var sent = 0
    private var ignored = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val variant = intent.getBooleanExtra("variant", false)
        val ignoreTaps = intent.getIntExtra("ignoreTaps", 0)
        val invisible = intent.getBooleanExtra("invisible", false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(TextView(this).apply {
            id = R.id.title; text = "Ahmad (test)"; textSize = 20f; setPadding(32, 48, 32, 24)
        })
        val counter = TextView(this).apply { id = R.id.sent_count; text = "sent=0"; textSize = 12f; setPadding(32, 0, 32, 0) }
        root.addView(counter)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        fun bubble(text: String, outgoing: Boolean) {
            val tv = TextView(this).apply {
                id = R.id.message_text
                this.text = text
                textSize = 16f
                setPadding(28, 18, 28, 18)
                setBackgroundColor(if (outgoing) Color.parseColor("#DCF8C6") else Color.parseColor("#EEEEEE"))
                maxWidth = (resources.displayMetrics.widthPixels * 0.65).toInt()
            }
            list.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (outgoing) Gravity.END else Gravity.START
                topMargin = 16
            })
        }
        bubble("السلام عليكم", false)
        bubble("هل أنت متاح غداً؟", false)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 16, 16, 16) }
        val input = EditText(this).apply {
            id = if (variant) R.id.input_v2 else R.id.input
            hint = "Message"
        }
        val send = Button(this).apply {
            id = if (variant) R.id.send_v2 else R.id.send
            text = "إرسال"
            contentDescription = "إرسال"
        }
        send.setOnClickListener {
            if (ignored < ignoreTaps) { ignored++; return@setOnClickListener }
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            sent++
            counter.text = "sent=$sent"
            input.setText("")
            if (!invisible) bubble(text, true)
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(bar)
        setContentView(root)
    }
}
