@file:OptIn(ExperimentalMaterial3Api::class)

package io.kestrel.research.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.kestrel.research.AppContainer
import io.kestrel.research.KestrelApp
import io.kestrel.research.ui.theme.KestrelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KestrelApp).container
        setContent { KestrelTheme { Root(container) } }
    }
}

@Composable
private fun Root(container: AppContainer) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val researchVm: ResearchViewModel = viewModel()
    val runtime by researchVm.runtime.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kestrel", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        OfflineBadge(container.device)
                    }
                },
                actions = {
                    val loaded = runtime.loaded.size
                    Text(
                        if (runtime.loading != null) "loading…" else if (loaded > 0) "$loaded model${if (loaded > 1) "s" else ""} ready" else "idle",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Triple("Research", Icons.Filled.Search, 0),
                    Triple("Library", Icons.AutoMirrored.Filled.List, 1),
                    Triple("Benchmark", Icons.Filled.Info, 2),
                    Triple("Settings", Icons.Filled.Settings, 3),
                )
                for ((label, icon, idx) in items) {
                    NavigationBarItem(selected = tab == idx, onClick = { tab = idx }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ResearchScreen(researchVm, container)
                1 -> LibraryScreen(container)
                2 -> BenchmarkScreen()
                else -> SettingsScreen(container)
            }
        }
    }
}
