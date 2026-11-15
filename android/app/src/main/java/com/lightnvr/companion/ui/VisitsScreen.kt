package com.lightnvr.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.AuthExpiredException
import com.lightnvr.companion.net.Camera
import com.lightnvr.companion.net.Visit
import com.lightnvr.companion.net.VisitFilter
import com.lightnvr.companion.net.VisitPerson
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Period(val label: String) { TODAY("Today"), YESTERDAY("Yesterday"), WEEK("Last 7 days") }

/** The "what was seen" filter (values the API's `what` takes; null = everything). */
private val WHAT = listOf(
    "real" to "All real activity",
    "people" to "People",
    "vehicles" to "Vehicles",
    "animals" to "Animals",
    "packages" to "Bags / packages",
    "other" to "Other objects",
    "motion" to "Motion only (lights, shadows…)",
    null to "Everything",
)

/**
 * Visits: motion on one or more cameras that belongs together, newest first -
 * filtered by what was seen, where (camera or camera group) and who. Tap one
 * to play it from its start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitsScreen(
    api: ApiClient,
    onPlay: (cameraId: Int, cameraName: String, at: Double) -> Unit,
    onSignedOut: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var period by rememberSaveable { mutableStateOf(Period.TODAY) }
    var what by rememberSaveable { mutableStateOf<String?>("real") }
    var cameraId by rememberSaveable { mutableStateOf<Int?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var visits by remember { mutableStateOf<List<Visit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var registry by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            cameras = api.cameras()
            registry = api.peopleNames()
        } catch (e: AuthExpiredException) {
            onSignedOut()
        } catch (e: IOException) {
            // the filters still work without names
        }
    }

    val filter = VisitFilter(person = person, what = what, cameraId = cameraId, group = group)
    LaunchedEffect(period, filter, reload) {
        visits = null
        while (true) {
            val today = LocalDate.now(zone)
            val (from, to) = when (period) {
                Period.TODAY -> today to today.plusDays(1)
                Period.YESTERDAY -> today.minusDays(1) to today
                Period.WEEK -> today.minusDays(6) to today.plusDays(1)
            }
            try {
                visits = api.visits(
                    from.atStartOfDay(zone).toEpochSecond(), to.atStartOfDay(zone).toEpochSecond(), filter,
                )
                error = null
            } catch (e: AuthExpiredException) {
                onSignedOut()
                return@LaunchedEffect
            } catch (e: IOException) {
                error = e.message ?: "Couldn't load visits"
            }
            delay(30_000)
        }
    }

    val cameraNames = cameras.associate { it.id to it.name }
    val groups = cameras.mapNotNull { it.group }.distinct().sortedBy { it.lowercase() }
    val names = (registry + visits.orEmpty().flatMap { v ->
        v.identities.orEmpty() + v.people.orEmpty().filter { it.kind == "known" || it.kind == "probable" }.mapNotNull { it.name }
    } + listOfNotNull(person?.takeIf { it != VisitFilter.STRANGERS })).distinct().sortedBy { it.lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visits") },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Period.entries.toList()) { p ->
                    FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Choice(
                        label = WHAT.first { it.first == what }.second,
                        active = what != "real",
                        options = WHAT.map { it.second },
                        onPick = { what = WHAT[it].first },
                    )
                }
                item {
                    val whereOptions = listOf("All cameras") + groups.map { "$it (group)" } + cameras.map { it.name }
                    Choice(
                        label = when {
                            cameraId != null -> cameraNames[cameraId] ?: "Camera $cameraId"
                            group != null -> "$group (group)"
                            else -> "All cameras"
                        },
                        active = cameraId != null || group != null,
                        options = whereOptions,
                        onPick = { i ->
                            when {
                                i == 0 -> { cameraId = null; group = null }
                                i <= groups.size -> { group = groups[i - 1]; cameraId = null }
                                else -> { cameraId = cameras[i - 1 - groups.size].id; group = null }
                            }
                        },
                    )
                }
                item {
                    val whoOptions = listOf("Everyone", "Strangers") + names
                    Choice(
                        label = when (person) {
                            null -> "Everyone"
                            VisitFilter.STRANGERS -> "Strangers"
                            else -> person!!
                        },
                        active = person != null,
                        options = whoOptions,
                        onPick = { i ->
                            person = when (i) {
                                0 -> null
                                1 -> VisitFilter.STRANGERS
                                else -> names[i - 2]
                            }
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val list = visits
                when {
                    error != null && list == null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    list.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("No visits match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (period != Period.WEEK) {
                            TextButton(onClick = { period = Period.WEEK }) { Text("Look back 7 days") }
                        }
                        if (filter != VisitFilter()) {
                            TextButton(onClick = { what = "real"; cameraId = null; group = null; person = null }) {
                                Text("Clear filters")
                            }
                        }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Text(
                                "${list.size} visit${if (list.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(list, key = { it.id }) { v ->
                            VisitCard(api, v, zone, period == Period.WEEK, cameraNames, onPlay)
                        }
                    }
                }
            }
        }
    }
}

/** A filter button that opens a list of choices. */
@Composable
private fun Choice(label: String, active: Boolean, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, text ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisitCard(
    api: ApiClient,
    v: Visit,
    zone: ZoneId,
    showDate: Boolean,
    cameraNames: Map<Int, String>,
    onPlay: (Int, String, Double) -> Unit,
) {
    val started = epoch(v.startedAt)
    val cams = v.cameraIds.orEmpty()
    var pick by remember { mutableStateOf(false) }
    val play: (Int) -> Unit = { id -> onPlay(id, cameraNames[id] ?: "Camera $id", started - 3) }
    val version = URLEncoder.encode(v.reviewedAt ?: "", "UTF-8")

    Card(
        onClick = {
            when (cams.size) {
                0 -> Unit
                1 -> play(cams[0])
                else -> pick = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (v.snapshotPath != null) {
                    val img = rememberRemoteImage(api, "/api/security/incidents/${v.id}/snapshot.jpg?v=$version", maxWidth = 480)
                    Box(
                        modifier = Modifier
                            .size(width = 132.dp, height = 74.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        if (cams.isNotEmpty()) {
                            Text(
                                "▶",
                                color = Color.White,
                                modifier = Modifier.background(Color(0x99000000), CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        buildString {
                            append(fmt(started, zone, showDate))
                            append(" · ").append(duration(v))
                            if (v.status == "open") append(" · in progress")
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val route = v.areasPath.orEmpty().joinToString(" → ")
                        .ifEmpty { cams.joinToString(", ") { cameraNames[it] ?: "Camera $it" } }
                    if (route.isNotEmpty()) Text(route, style = MaterialTheme.typography.bodySmall)
                    Text(
                        seenLine(v),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val people = v.people.orEmpty()
            if (people.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    people.forEachIndexed { n, p -> PersonChip(api, v.id, n, p, version) }
                }
            } else if (!v.identities.isNullOrEmpty()) {
                Text(v.identities.joinToString(", "), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            v.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if ((v.status == "open" || v.reviewStatus == "pending") && (v.counts.orEmpty().isNotEmpty() || !v.identities.isNullOrEmpty())) {
                Text(
                    "AI review pending - names and description follow once the NVR is quiet.",
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pick) {
                Text("Play from ${fmt(started, zone, false)} on:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cams.forEach { id ->
                        AssistChip(onClick = { pick = false; play(id) }, label = { Text("▶ ${cameraNames[id] ?: "Camera $id"}") })
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonChip(api: ApiClient, visitId: Int, n: Int, p: VisitPerson, version: String) {
    val (label, weight, style, color) = when (p.kind) {
        "known" -> Quad(p.name ?: "Known person", FontWeight.SemiBold, FontStyle.Normal, Color.Unspecified)
        "probable" -> Quad(p.name?.let { "possibly $it" } ?: "face unclear", FontWeight.Normal, FontStyle.Italic, Color.Unspecified)
        "stranger" -> Quad("Stranger", FontWeight.Normal, FontStyle.Normal, Color(0xFFD29922))
        else -> Quad("face not visible", FontWeight.Normal, FontStyle.Normal, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val img = if (p.photo) rememberRemoteImage(api, "/api/security/incidents/$visitId/people/$n.jpg?v=$version", maxWidth = 96) else null
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
            img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = weight, fontStyle = style, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

private data class Quad(val label: String, val weight: FontWeight, val style: FontStyle, val color: Color)

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

private fun epoch(iso: String): Double = try {
    Instant.parse(iso).toEpochMilli() / 1000.0
} catch (e: java.time.format.DateTimeParseException) {
    0.0
}

private fun fmt(t: Double, zone: ZoneId, withDate: Boolean): String =
    (if (withDate) DAY_TIME else TIME).format(Instant.ofEpochMilli((t * 1000).toLong()).atZone(zone))

private fun duration(v: Visit): String {
    val end = v.endedAt ?: return "ongoing"
    val s = maxOf(0L, Math.round(epoch(end) - epoch(v.startedAt)))
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

/** What was seen: kinds only (the per-kind numbers count re-appearances). */
internal fun seenLine(v: Visit): String {
    val kinds = v.counts.orEmpty().keys.map { if (it == "person") "people" else "${it}s" }
    return kinds.joinToString(", ").ifEmpty { "motion only - nothing recognised" }
}
