@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pnixon.newz.data.ArticleEntity
import newz.core.Ranker
import newz.core.Scored
import newz.core.TrustLevel

fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

fun ago(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - timestamp).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 48 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

@Composable
fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FrontPage(
    state: FrontState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Scored) -> Unit,
    onWhy: (Scored) -> Unit,
) {
    val context = LocalContext.current
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        val visible = state.result.visible
        if (visible.isEmpty()) {
            // A scrollable holder so pull-to-refresh still works when empty.
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillParentMaxSize()) {
                        EmptyMessage(
                            if (!state.loaded || refreshing) "Fetching your sources…"
                            else "Nothing to show yet. Pull down to refresh, or check the Sources and Filtered tabs."
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.item.id }) { scored ->
                    ArticleRow(
                        scored = scored,
                        article = state.articles[scored.item.id],
                        subtitle = null,
                        onClick = {
                            if (openUrl(context, scored.item.url)) onOpen(scored)
                        },
                        onWhy = { onWhy(scored) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun FilteredPage(state: FrontState, onWhy: (Scored) -> Unit) {
    val hidden = state.result.hidden
    if (hidden.isEmpty()) {
        EmptyMessage("Nothing has been filtered out. Every article the app holds is on the front page.")
        return
    }
    val shown = hidden.take(500)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "${hidden.size} articles are held back from the front page. Each one says why." +
                    if (hidden.size > shown.size) " Showing the newest ${shown.size}." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider()
        }
        items(shown, key = { it.item.id }) { scored ->
            ArticleRow(
                scored = scored,
                article = state.articles[scored.item.id],
                subtitle = scored.hiddenReason,
                onClick = { onWhy(scored) },
                onWhy = { onWhy(scored) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ArticleRow(
    scored: Scored,
    article: ArticleEntity?,
    subtitle: String?,
    onClick: () -> Unit,
    onWhy: () -> Unit,
) {
    val item = scored.item
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtitle == null && article?.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${item.sourceName} · ${ago(item.timestamp)}" +
                    if (subtitle == null) " · score ${Ranker.fmt(scored.total)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (scored.alsoReportedBy.isNotEmpty()) {
                Text(
                    "Also: ${scored.alsoReportedBy.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onWhy) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "Why this article is ranked here",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TrustChips(selected: TrustLevel, onSelect: (TrustLevel) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TrustLevel.TRUSTED, TrustLevel.NEUTRAL, TrustLevel.LOW, TrustLevel.UNRATED, TrustLevel.BLOCKED).forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

/** The full account of one article's score, and the controls that change it. */
@Composable
fun WhySheet(
    scored: Scored,
    article: ArticleEntity?,
    onDismiss: () -> Unit,
    onTrust: (TrustLevel) -> Unit,
    onRead: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val item = scored.item
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${item.sourceName} · ${ago(item.timestamp)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!article?.summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(article?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            val reason = scored.hiddenReason
            if (reason != null) {
                Text("Not on the front page", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }

            if (scored.contributions.isNotEmpty()) {
                Text("Score ${Ranker.fmt(scored.total)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Each line is value × weight. Change the weights on the Algorithm tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                scored.contributions.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.signal, style = MaterialTheme.typography.bodyLarge)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${Ranker.fmt(c.raw)} × ${Ranker.fmt(c.weight)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            (if (c.points >= 0) "+" else "") + Ranker.fmt(c.points),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            Text("Your trust in ${item.sourceName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            TrustChips(selected = item.trust, onSelect = onTrust)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (openUrl(context, item.url)) onRead(true)
                    onDismiss()
                }) { Text("Open article") }
                OutlinedButton(onClick = { onRead(!item.read) }) {
                    Text(if (item.read) "Mark unread" else "Mark read")
                }
            }
        }
    }
}
