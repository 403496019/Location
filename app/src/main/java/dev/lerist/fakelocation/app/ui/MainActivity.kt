package dev.lerist.fakelocation.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "FakeLocation Repro v1\nPhase 1 skeleton"
            },
        )
    }
}
