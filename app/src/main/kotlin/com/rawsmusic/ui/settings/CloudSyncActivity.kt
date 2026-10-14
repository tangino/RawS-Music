package com.rawsmusic.ui.settings

import android.os.Bundle

class CloudSyncActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudSyncScreen(onBack = { finish() })
        }
    }
}
