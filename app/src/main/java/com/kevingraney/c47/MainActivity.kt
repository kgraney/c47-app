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

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }

    override fun onPause() {
        vm.save()
        super.onPause()
    }

    override fun onDestroy() {
        vm.shutdown()
        super.onDestroy()
    }
}
