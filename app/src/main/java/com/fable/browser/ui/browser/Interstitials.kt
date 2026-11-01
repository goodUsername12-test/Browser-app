package com.fable.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fable.browser.engine.FindMatch
import com.fable.browser.engine.Interstitial
import com.fable.browser.util.UrlUtils

@Composable
fun InterstitialScreen(
    interstitial: Interstitial,
    onProceed: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val host = UrlUtils.displayHost(interstitial.url)
    when (interstitial) {
        is Interstitial.HttpsOnly -> InterstitialBody(
            modifier = modifier,
            icon = Icons.Outlined.LockOpen,
            title = "This site does not support HTTPS",
            body = "$host was requested over plain HTTP. Anyone on the network between you " +
                "and the site can read and modify the page. HTTPS-only mode blocked it.",
            primaryLabel = "Go back",
            onPrimary = onBack,
            secondaryLabel = "Continue to HTTP site",
            onSecondary = { onProceed(interstitial.url) },
            secondaryIsRisky = true
        )

        is Interstitial.Ssl -> InterstitialBody(
            modifier = modifier,
            icon = Icons.Outlined.GppMaybe,
            title = "The connection is not private",
            body = "${interstitial.reason} Fable will not load $host because the identity of " +
                "the server cannot be verified. There is no click-through for certificate " +
                "errors — that is deliberate.",
            primaryLabel = "Go back",
            onPrimary = onBack,
            secondaryLabel = "Try again",
            onSecondary = onRetry,
            secondaryIsRisky = false
        )

        is Interstitial.Network -> InterstitialBody(
            modifier = modifier,
            icon = Icons.Outlined.CloudOff,
            title = "This page did not load",
            body = "${interstitial.reason}\n\n$host",
            primaryLabel = "Try again",
            onPrimary = onRetry,
            secondaryLabel = "Go back",
            onSecondary = onBack,
            secondaryIsRisky = false
        )
    }
}

@Composable
private fun InterstitialBody(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    secondaryIsRisky: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
            Text(
                secondaryLabel,
                color = if (secondaryIsRisky) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun FindBar(
    query: String,
    match: FindMatch?,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Find in page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Text(
                text = match?.let { "${it.current}/${it.total}" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close find bar")
            }
        }
    }
}
