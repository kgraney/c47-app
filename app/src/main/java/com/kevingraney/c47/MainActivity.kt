package com.kevingraney.c47

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.kevingraney.c47.engine.C47Engine

class MainActivity : AppCompatActivity() {

    private val engine = C47Engine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine.init(filesDir)
        Log.i("c47", "C47Engine loaded; libc47.so linked. Engine bodies are stubs until Phase 2.")

        setContent {
            MaterialTheme {
                CalculatorScreen()
            }
        }
    }
}
