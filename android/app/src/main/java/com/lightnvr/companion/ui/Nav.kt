package com.lightnvr.companion.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightnvr.companion.data.SessionStore
import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.AuthExpiredException
import com.lightnvr.companion.net.Me
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** "Play camera N from time T" - from an alert notification. */
data class DeepLink(val cameraId: Int, val at: Double)

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Default.PlayArrow),
    VISITS("Visits", Icons.AutoMirrored.Filled.List),
    SECURITY("Security", Icons.Default.Lock),
    SETTINGS("Settings", Icons.Default.Settings),
}

private fun NavHostController.openPlayback(cameraId: Int, name: String, at: Double) =
    navigate("playback/$cameraId?name=${Uri.encode(name)}&at=$at")

@Composable
fun LightNvrNav(api: ApiClient, store: SessionStore, deepLinks: MutableStateFlow<DeepLink?>) {
    val nav = rememberNavController()
    val start = remember { if (store.isSignedIn) "main" else "connect" }
    val toConnect: () -> Unit = {
        nav.navigate("connect") { popUpTo(nav.graph.id) { inclusive = true } }
    }

    // An alert was tapped: play that camera from just before that moment.
    val link by deepLinks.collectAsState()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        deepLinks.value = null
        if (!store.isSignedIn) return@LaunchedEffect
        val name = try {
            api.cameras().firstOrNull { it.id == l.cameraId }?.name
        } catch (e: IOException) {
            null
        } ?: "Camera ${l.cameraId}"
        nav.openPlayback(l.cameraId, name, l.at - 5)
    }

    NavHost(navController = nav, startDestination = start) {
        composable("connect") {
            ConnectScreen(
                api = api,
                store = store,
                onSignedIn = { nav.navigate("main") { popUpTo(nav.graph.id) { inclusive = true } } },
            )
        }
        composable("main") {
            MainTabs(
                api = api,
                store = store,
                onOpenLive = { id, name, audio -> nav.navigate("live/$id?name=${Uri.encode(name)}&audio=$audio") },
                onPlay = { id, name, at -> nav.openPlayback(id, name, at) },
                onWebSettings = { nav.navigate("websettings") },
                onSignedOut = toConnect,
            )
        }
        composable(
            route = "live/{id}?name={name}&audio={audio}",
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("audio") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: 0
            val name = entry.arguments?.getString("name").orEmpty()
            LiveScreen(
                api = api,
                cameraId = id,
                cameraName = name,
                hasAudio = entry.arguments?.getBoolean("audio") ?: false,
                onBack = { nav.popBackStack() },
                onPlayback = { nav.openPlayback(id, name, System.currentTimeMillis() / 1000.0 - 60) },
                onSignedOut = toConnect,
            )
        }
        composable(
            route = "playback/{id}?name={name}&at={at}",
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                // A string: a Float can't hold unix seconds (it's off by minutes).
                navArgument("at") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: 0
            val name = entry.arguments?.getString("name").orEmpty()
            val at = entry.arguments?.getString("at")?.toDoubleOrNull()
                ?: (System.currentTimeMillis() / 1000.0 - 60)
            PlaybackScreen(
                api = api,
                cameraId = id,
                cameraName = name,
                startAt = at,
                onBack = { nav.popBackStack() },
                onLive = {
                    nav.popBackStack()
                    nav.navigate("live/$id?name=${Uri.encode(name)}")
                },
                onSignedOut = toConnect,
            )
        }
        composable("websettings") {
            WebSettingsScreen(api = api, store = store, onBack = { nav.popBackStack() }, onSignedOut = toConnect)
        }
    }
}

@Composable
private fun MainTabs(
    api: ApiClient,
    store: SessionStore,
    onOpenLive: (Int, String, Boolean) -> Unit,
    onPlay: (Int, String, Double) -> Unit,
    onWebSettings: () -> Unit,
    onSignedOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var me by remember { mutableStateOf<Me?>(null) }
    LaunchedEffect(Unit) {
        me = try {
            api.me()
        } catch (e: AuthExpiredException) {
            onSignedOut()
            null
        } catch (e: IOException) {
            null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.LIVE -> LiveGridScreen(
                    api = api,
                    onOpen = { c -> onOpenLive(c.id, c.name, c.hasAudio) },
                    onSignedOut = onSignedOut,
                )
                Tab.VISITS -> VisitsScreen(api = api, onPlay = onPlay, onSignedOut = onSignedOut)
                Tab.SECURITY -> SecurityScreen(api = api, isAdmin = me?.isAdmin == true, onSignedOut = onSignedOut)
                Tab.SETTINGS -> SettingsScreen(
                    api = api,
                    store = store,
                    me = me,
                    onWebSettings = onWebSettings,
                    onSignedOut = onSignedOut,
                )
            }
        }
    }
}
