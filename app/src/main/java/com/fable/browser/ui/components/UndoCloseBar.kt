package com.fable.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fable.browser.engine.TabManager

@Composable
fun UndoCloseBar(
    closed: TabManager.ClosedTab?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var last by remember { mutableStateOf(closed) }
    if (closed != null) last = closed
    AnimatedVisibility(
        visible = closed != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        val title = last?.tab?.title?.value.orEmpty().ifBlank { "Tab" }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Text(
                    text = "Tab closed · $title",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onUndo) {
                    Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
