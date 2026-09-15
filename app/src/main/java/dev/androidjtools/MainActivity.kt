package dev.androidjtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.ui.theme.AndroidDjToolsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providers = FixtureAppProviders.create()
        setContent {
            AndroidDjToolsTheme {
                AndroidDjToolsApp(providers)
            }
        }
    }
}
