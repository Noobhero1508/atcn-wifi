package io.github.noobhero1508.atcnwifi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import io.github.noobhero1508.atcnwifi.ui.AtcnWifiApp
import io.github.noobhero1508.atcnwifi.ui.MonitorUiFacade
import io.github.noobhero1508.atcnwifi.ui.theme.AtcnWifiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtcnWifiTheme {
                val facade = remember { MonitorUiFacade(applicationContext) }
                AtcnWifiApp(facade = facade)
            }
        }
    }
}
