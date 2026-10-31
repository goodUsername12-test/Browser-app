package com.fable.browser.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fable.browser.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place that needs an Activity: file pickers, share sheets, external
 * intents, home-screen shortcuts and the biometric prompt. Held weakly so a
 * destroyed Activity cannot leak through the singleton engine layer.
 */
@Singleton
class ActivityBridge @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private var activityRef: WeakReference<ComponentActivity>? = null
    private var filePicker: ActivityResultLauncher<Intent>? = null
    private var pendingFileResult: ((List<Uri>) -> Unit)? = null
    private var permissionRequester: ActivityResultLauncher<Array<String>>? = null
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null

    fun activity(): ComponentActivity? = activityRef?.get()

    /** Context for engine callbacks that need one (file prompts, intents). */
    fun context(): Context = activityRef?.get() ?: appContext

    /** Called from MainActivity.onCreate, before setContent. */
    fun bind(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        filePicker = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = pendingFileResult
            pendingFileResult = null
            val data = result.data
            val uris = when {
                data == null -> emptyList()
                data.clipData != null -> (0 until data.clipData!!.itemCount)
                    .map { data.clipData!!.getItemAt(it).uri }
                data.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
            callback?.invoke(uris)
        }
        permissionRequester = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val callback = pendingPermissionResult
            pendingPermissionResult = null
            callback?.invoke(grants.isNotEmpty() && grants.values.all { it })
        }
    }

    fun unbind(activity: ComponentActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            filePicker = null
            permissionRequester = null
            pendingFileResult = null
            pendingPermissionResult = null
        }
    }

    /**
     * OS-level runtime permission request, triggered only when a site asks for
     * the matching capability and the user approved it in the site prompt.
     */
    fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val launcher = permissionRequester
        if (launcher == null || permissions.isEmpty()) {
            onResult(false)
            return
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        pendingPermissionResult = onResult
        runCatching { launcher.launch(missing.toTypedArray()) }.onFailure {
            pendingPermissionResult = null
            onResult(false)
        }
    }

    /** Web page file upload (`<input type="file">`). */
    fun requestFiles(
        mimeTypes: Array<String>,
        allowMultiple: Boolean,
        onResult: (List<Uri>) -> Unit
    ) {
        val launcher = filePicker
        if (launcher == null) {
            onResult(emptyList())
            return
        }
        pendingFileResult = onResult
        fun pickerIntent(action: String) = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Several accepted types: "*/*" + the list, otherwise some pickers
            // only show the first type.
            type = if (mimeTypes.size == 1) mimeTypes[0].ifBlank { "*/*" } else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
        // Prefer the system document picker; fall back to any file app.
        val launched = runCatching { launcher.launch(pickerIntent(Intent.ACTION_OPEN_DOCUMENT)) }.isSuccess ||
            runCatching { launcher.launch(pickerIntent(Intent.ACTION_GET_CONTENT)) }.isSuccess
        if (!launched) {
            pendingFileResult = null
            onResult(emptyList())
        }
    }

    /** Hand intent://, mailto:, tel:, market:, geo: … to the OS. */
    fun openExternal(url: String): Boolean {
        val intent = runCatching {
            if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (activity() ?: appContext).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // intent:// URLs can carry a browser fallback URL
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) openExternal(fallback) else {
                toast(appContext.getString(R.string.no_app_for_link))
                false
            }
        }
    }

    fun sharePage(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (activity() ?: appContext).startActivity(chooser) }
    }

    fun openFile(uri: Uri, mimeType: String?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { (activity() ?: appContext).startActivity(intent) }
            .onFailure { toast(appContext.getString(R.string.cannot_open_file)) }
    }

    /** "Add to home screen" — a launcher shortcut that deep-links back into Fable. */
    fun addToHomeScreen(title: String, url: String, favicon: Bitmap?) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(appContext)) {
            toast(appContext.getString(R.string.shortcut_unsupported))
            return
        }
        val icon = favicon?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(appContext, R.mipmap.ic_launcher)
        val shortcut = ShortcutInfoCompat.Builder(appContext, "fable-$url")
            .setShortLabel(title.ifBlank { url }.take(24))
            .setLongLabel(title.ifBlank { url })
            .setIcon(icon)
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(appContext.packageName)
            })
            .build()
        ShortcutManagerCompat.requestPinShortcut(appContext, shortcut, null)
    }

    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}
