package com.fable.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fable.browser.util.CrashReporter

private const val ISSUE_URL = "https://github.com/goodUsername12-test/Browser-app/issues/new"

/** Shows the saved report of the previous crash, if there is one. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pendingReport(context)) }
    val text = report ?: return

    fun dismiss() {
        CrashReporter.clear(context)
        report = null
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("Fable Browser stopped last time") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Copy this report or send it on GitHub so the problem can be fixed.")
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { openIssue(context, text); dismiss() }) { Text("Report on GitHub") }
        },
        dismissButton = {
            TextButton(onClick = { copy(context, text); dismiss() }) { Text("Copy") }
        }
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Fable Browser crash", text))
    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
}

private fun openIssue(context: Context, text: String) {
    val firstLine = text.lineSequence().drop(4).firstOrNull { it.isNotBlank() }?.take(80) ?: "crash"
    val uri = Uri.parse(ISSUE_URL).buildUpon()
        .appendQueryParameter("title", "Crash: $firstLine")
        .appendQueryParameter("body", "```\n${text.take(6_000)}\n```")
        .build()
    val view = Intent(Intent.ACTION_VIEW, uri)
    // Open the report in another browser, never in Fable itself.
    val chooser = Intent.createChooser(view, "Open GitHub with").apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, "com.fable.browser.MainActivity")))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
