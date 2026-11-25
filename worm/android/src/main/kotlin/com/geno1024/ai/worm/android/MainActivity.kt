package com.geno1024.ai.worm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.geno1024.ai.worm.android.ui.WormApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WormApp owns theme state so the settings screen can preview a change immediately.
        setContent { WormApp() }
    }
}
