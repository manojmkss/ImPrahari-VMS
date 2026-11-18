package com.imprahari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.AuthExpiredException
import com.imprahari.app.net.Camera
import java.io.IOException

/**
 * Every camera live at once (sub streams), optionally one camera group only.
 * Tiles stream only while visible - a scrolled-away tile closes its socket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveGridScreen(
    api: ApiClient,
    onOpen: (Camera) -> Unit,
    onSignedOut: () -> Unit,
) {
    var cameras by remember { mutableStateOf<List<Camera>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        error = null
        try {
            cameras = api.cameras().filter { it.enabled }
        } catch (e: AuthExpiredException) {
            onSignedOut()
        } catch (e: IOException) {
            error = e.message ?: "Couldn't load cameras"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live") },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val list = cameras
            val groups = list.orEmpty().mapNotNull { it.group }.distinct().sortedBy { it.lowercase() }
            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All") })
                    }
                    items(groups) { g ->
                        FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    error != null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text(
                        "No cameras yet - add them in Settings → Web settings.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> {
                        val shown = list.filter { group == null || it.group.equals(group, ignoreCase = true) }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 300.dp),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.id }) { camera ->
                                LiveTile(api, camera, onClick = { onOpen(camera) }, onSignedOut = onSignedOut)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTile(api: ApiClient, camera: Camera, onClick: () -> Unit, onSignedOut: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        LiveVideo(
            api = api,
            cameraId = camera.id,
            quality = "sub",
            muted = true,
            onSignedOut = onSignedOut,
            modifier = Modifier.fillMaxSize(),
            compact = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (camera.status == "online") StatusOnline else StatusOffline)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                camera.name,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // On top of the video view, which would otherwise see the touches.
        Box(modifier = Modifier.matchParentSize().clickable(onClick = onClick))
    }
}
