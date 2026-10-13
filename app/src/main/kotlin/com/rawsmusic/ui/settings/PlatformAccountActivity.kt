package com.rawsmusic.ui.settings

import android.os.Bundle

class PlatformAccountActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlatformAccountScreen(onBack = { finish() })
        }
    }
}
