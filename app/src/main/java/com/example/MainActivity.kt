package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.components.MainNavigationScaffold
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels {
        val app = application as XtremeMusicApp
        PlayerViewModel.provideFactory(app.repository, app.playbackManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainNavigationScaffold(viewModel = playerViewModel)
            }
        }
    }
}
