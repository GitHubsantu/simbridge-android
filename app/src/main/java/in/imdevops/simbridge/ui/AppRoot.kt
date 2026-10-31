package `in`.imdevops.simbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import `in`.imdevops.simbridge.R
import `in`.imdevops.simbridge.SimBridgeApplication

private enum class Tab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_tab_home),
    MESSAGES("Messages", R.drawable.ic_tab_messages),
    API("API", R.drawable.ic_tab_api),
    TUNNEL("Tunnel", R.drawable.ic_tab_tunnel),
    DIAGNOSTICS("Diagnostics", R.drawable.ic_tab_diagnostics),
}

@Composable
fun AppRoot(app: SimBridgeApplication) {
    var selected by rememberSaveable { mutableStateOf(Tab.HOME.ordinal) }
    SimBridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selected == tab.ordinal,
                                onClick = { selected = tab.ordinal },
                                icon = { Icon(painterResource(tab.icon), contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                when (Tab.entries[selected]) {
                    Tab.HOME -> DashboardScreen(app, modifier)
                    Tab.MESSAGES -> MessagesScreen(app, modifier)
                    Tab.API -> ApiScreen(app, modifier)
                    Tab.TUNNEL -> ScrollColumn(modifier) {
                        ScreenHeader("Tunnel", "Cloudflare connection to your public hostname")
                        TunnelCard(app)
                        CloudflaredUpdateCard(app)
                    }
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(app, modifier)
                }
            }
        }
    }
}

@Composable
internal fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
