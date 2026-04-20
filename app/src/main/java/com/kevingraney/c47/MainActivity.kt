package com.kevingraney.c47

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.kevingraney.c47.engine.CalculatorViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var vm: CalculatorViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = CalculatorViewModel().also { it.init(filesDir) }

        setContent {
            MaterialTheme {
                CalculatorScreen(vm)
            }
        }
    }

    // Frame pump lives in CalculatorScreen as a vsync-aligned LaunchedEffect,
    // so it starts/stops with composition — no onStart/onStop wiring needed.

    override fun onPause() {
        vm.save()
        super.onPause()
    }

    override fun onDestroy() {
        vm.shutdown()
        super.onDestroy()
    }
}
