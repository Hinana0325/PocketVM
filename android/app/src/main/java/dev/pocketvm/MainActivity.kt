package dev.pocketvm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.pocketvm.ui.PocketVmRoot
import dev.pocketvm.ui.theme.PocketVmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketVmTheme {
                PocketVmRoot()
            }
        }
    }
}
