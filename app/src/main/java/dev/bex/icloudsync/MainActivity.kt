package dev.bex.icloudsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.bex.icloudsync.ui.ICloudSyncApp
import dev.bex.icloudsync.ui.MainViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ICloudSyncApp(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
