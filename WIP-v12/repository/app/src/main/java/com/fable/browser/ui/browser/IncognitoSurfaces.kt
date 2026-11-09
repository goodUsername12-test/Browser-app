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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun IncognitoDisclosure(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        icon = {
            Icon(
                Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("What incognito actually does") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "It protects you from:",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Protects("No local traces: no history, cookies, cache, site storage, form data or thumbnails are written to this device.")
                Protects("Cross-site tracking: each incognito tab can get its own cookie jar and storage partition, so two sites cannot correlate you.")
                Protects("Fingerprinting: user agent, platform, screen size, timezone, canvas, WebGL, audio and timers are spoofed or noised per session.")
                Protects("Screenshots and the app switcher: incognito screens are marked secure, so the system cannot capture them.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "It does NOT protect you from:",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                DoesNot("Your ISP, mobile carrier, VPN provider or a network operator watching traffic — use the proxy/Tor option or a VPN for that.")
                DoesNot("Your employer's or school's device management and network monitoring.")
                DoesNot("Any site you sign in to. Once you log in, you have identified yourself.")
                DoesNot("A compromised or rooted device, or malware/keyloggers running on it.")
                DoesNot("Files you explicitly choose to download, and bookmarks you explicitly save.")
            }
        },
        confirmButton = { Button(onClick = onAcknowledge) { Text("I understand") } }
    )
}

@Composable
private fun Protects(text: String) = Bullet(text, Icons.Outlined.Check, true)

@Composable
private fun DoesNot(text: String) = Bullet(text, Icons.Outlined.Close, false)

@Composable
private fun Bullet(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, good: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun IncognitoLockScreen(onUnlock: () -> Unit, onLeaveIncognito: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(18.dp))
        Text("Incognito is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your private tabs are still in memory and still hidden. Authenticate to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        TextButton(onClick = onLeaveIncognito, modifier = Modifier.fillMaxWidth()) {
            Text("Go to normal tabs")
        }
    }
}
