package com.geno1024.ai.worm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.geno1024.ai.worm.android.ui.WormApp
import com.geno1024.ai.worm.android.ui.WormTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WormTheme {
                WormApp()
            }
        }
    }
}
