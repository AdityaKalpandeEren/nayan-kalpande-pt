package com.kalpande.nkpt.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kalpande.nkpt.ui.components.Intro
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.domain.MarketHours
import com.kalpande.nkpt.ui.components.Dot
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.MarketSwitch
import com.kalpande.nkpt.ui.screens.AnalyticsScreen
import com.kalpande.nkpt.ui.screens.DetailScreen
import com.kalpande.nkpt.ui.screens.OrdersScreen
import com.kalpande.nkpt.ui.screens.PortfolioScreen
import com.kalpande.nkpt.ui.screens.SearchScreen
import com.kalpande.nkpt.ui.screens.SettingsScreen
import com.kalpande.nkpt.ui.screens.WatchlistScreen
import com.kalpande.nkpt.ui.theme.Palette
import java.net.URLDecoder
import java.net.URLEncoder

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("watchlist", "Watchlist", Icons.Outlined.ShowChart),
    Tab("orders", "Orders", Icons.Outlined.ReceiptLong),
    Tab("portfolio", "Portfolio", Icons.Outlined.AccountBalanceWallet),
    Tab("analytics", "Analytics", Icons.Outlined.Insights),
    Tab("settings", "Settings", Icons.Outlined.Settings),
)

fun NavHostController.openInstrument(i: Instrument) =
    navigate("detail/${i.market}/${URLEncoder.encode(i.key, "UTF-8")}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route.orEmpty()
    val snack = remember { SnackbarHostState() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.start()
            if (e == Lifecycle.Event.ON_STOP) vm.stop()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.stop() }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
    }

    val isTab = tabs.any { it.route == route } || route.isEmpty()
    var intro by rememberSaveable { mutableStateOf(!vm.offline) }
    LaunchedEffect(Unit) {
        if (!intro) return@LaunchedEffect
        delay(1600)
        withTimeoutOrNull(2500) { snapshotFlow { vm.ui.value.instrumentCount[vm.ui.value.market] }.first { it != null } }
        intro = false
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (isTab) Column(
                Modifier.fillMaxWidth().background(Palette.Bg).statusBarsPadding().padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MarketSwitch(ui.market, vm::setMarket)
                val open = MarketHours.isOpen(ui.market, System.currentTimeMillis() / 1000)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Dot(if (open) Palette.Up else Palette.Muted); HSpace(5)
                    Text(MarketHours.statusText(ui.market, System.currentTimeMillis() / 1000), fontSize = 11.sp, color = Palette.Muted)
                }
            }
        },
        bottomBar = {
            if (isTab) NavigationBar(containerColor = Palette.Surface) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route || (route.isEmpty() && t.route == "watchlist"),
                        onClick = { nav.navigate(t.route) { popUpTo("watchlist") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Accent, selectedTextColor = Palette.Accent,
                            indicatorColor = Palette.Accent.copy(alpha = 0.14f),
                            unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "watchlist", modifier = Modifier.fillMaxSize().background(Palette.Bg).padding(pad)) {
            composable("watchlist") { WatchlistScreen(vm, ui, onSearch = { nav.navigate("search") }, onOpen = { nav.openInstrument(it) }) }
            composable("orders") { OrdersScreen(vm, ui, onOpen = { nav.openInstrument(it) }) }
            composable("portfolio") { PortfolioScreen(vm, ui, onOpen = { nav.openInstrument(it) }) }
            composable("analytics") { AnalyticsScreen(vm, ui) }
            composable("settings") { SettingsScreen(vm, ui) }
            composable("search") { SearchScreen(vm, ui, onBack = { nav.popBackStack() }, onOpen = { nav.openInstrument(it) }) }
            composable(
                "detail/{market}/{key}",
                arguments = listOf(navArgument("market") { type = NavType.StringType }, navArgument("key") { type = NavType.StringType }),
            ) { e ->
                val m = Market.valueOf(e.arguments!!.getString("market")!!)
                val key = URLDecoder.decode(e.arguments!!.getString("key")!!, "UTF-8")
                DetailScreen(vm, ui, m, key, onBack = { nav.popBackStack() })
            }
        }
    }
    AnimatedVisibility(intro, enter = fadeIn(), exit = fadeOut(tween(450)) + scaleOut(tween(450), targetScale = 1.06f)) {
        Intro(if (ui.instrumentCount.isEmpty()) "Loading NSE & US symbol lists…" else "Ready")
    }
    }
}

