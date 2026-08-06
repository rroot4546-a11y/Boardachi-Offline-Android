package com.privateboard.clinical

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClinicalTheme(vm.dark) {
                when (val state = vm.loadState) {
                    CorpusLoadState.Loading -> LibraryLoadingScreen()
                    is CorpusLoadState.Failed -> LibraryErrorScreen(state.message, vm::loadCorpus)
                    is CorpusLoadState.Ready -> ClinicalApp(vm)
                }
            }
        }
    }
}

@Composable
private fun LibraryLoadingScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text("Preparing your offline library", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(
                "Opening 11,580 questions. This can take a few seconds the first time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LibraryErrorScreen(message: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text("Library could not be opened", fontSize = 23.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = retry) { Text("Try again") }
        }
    }
}

data class NavItem(val screen: AppScreen, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(AppScreen.HOME, "Today", Icons.Outlined.Home),
    NavItem(AppScreen.LIBRARY, "Library", Icons.Outlined.MenuBook),
    NavItem(AppScreen.SEARCH, "Search", Icons.Outlined.Search),
    NavItem(AppScreen.STATS, "Progress", Icons.Outlined.Insights)
)

@Composable
fun ClinicalApp(vm: MainViewModel) {
    val root = vm.screen in navItems.map { it.screen }
    Scaffold(
        bottomBar = {
            if (root) NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = vm.screen == item.screen,
                        onClick = { vm.screen = item.screen },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (vm.screen) {
                AppScreen.HOME -> HomeScreen(vm)
                AppScreen.LIBRARY -> LibraryScreen(vm)
                AppScreen.SEARCH -> SearchScreen(vm)
                AppScreen.STATS -> StatsScreen(vm)
                AppScreen.BOOK -> BookScreen(vm)
                AppScreen.SESSION -> SessionScreen(vm)
                AppScreen.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
