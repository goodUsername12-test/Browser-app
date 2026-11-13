package com.fable.browser.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fable.browser.engine.RecAudioSource
import com.fable.browser.engine.RecBitrate
import com.fable.browser.engine.RecFrameRate
import com.fable.browser.engine.RecOrientation
import com.fable.browser.engine.RecResolution
import com.fable.browser.engine.ScreenRecorderManager
import com.fable.browser.ui.components.ListDivider
import com.fable.browser.ui.components.SectionHeader
import com.fable.browser.ui.components.SettingRow
import com.fable.browser.ui.components.SwitchRow

private enum class RecorderDialogType {
    NONE,
    RESOLUTION,
    BITRATE,
    ORIENTATION,
    AUDIO_SOURCE,
    FRAME_RATE
}

@Composable
fun ScreenRecorderSettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val isRecording by ScreenRecorderManager.isRecording.collectAsStateWithLifecycle()
    val elapsedSec by ScreenRecorderManager.elapsedSeconds.collectAsStateWithLifecycle()
    val lastSaved by ScreenRecorderManager.lastSavedFile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf(RecorderDialogType.NONE) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        ScreenRecorderManager.startWithProjectionResult(
            context = context,
            resultCode = res.resultCode,
            data = res.data,
            config = s.toRecorderConfig()
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val cfg = s.toRecorderConfig()
        if (cfg.zeroLagBlinkMode && cfg.audioSource != RecAudioSource.SYSTEM && cfg.audioSource != RecAudioSource.SYSTEM_AND_MIC) {
            ScreenRecorderManager.startDirectWindowRecording(context, cfg)
        } else {
            val intent = ScreenRecorderManager.createCaptureIntent(context)
            if (intent != null) projectionLauncher.launch(intent)
            else ScreenRecorderManager.startDirectWindowRecording(context, cfg)
        }
    }

    val startRecordingAction = {
        val cfg = s.toRecorderConfig()
        val needMic = cfg.audioSource == RecAudioSource.MIC || cfg.audioSource == RecAudioSource.SYSTEM_AND_MIC
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (needMic && !hasMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (cfg.zeroLagBlinkMode && cfg.audioSource != RecAudioSource.SYSTEM && cfg.audioSource != RecAudioSource.SYSTEM_AND_MIC) {
            ScreenRecorderManager.startDirectWindowRecording(context, cfg)
        } else {
            val intent = ScreenRecorderManager.createCaptureIntent(context)
            if (intent != null) projectionLauncher.launch(intent)
            else ScreenRecorderManager.startDirectWindowRecording(context, cfg)
        }
    }

    SettingsPage(title = "Запись экрана · Screen Recorder", onBack = onBack, viewModel = viewModel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = {
                    if (isRecording) {
                        ScreenRecorderManager.stopRecording()
                    } else {
                        startRecordingAction()
                    }
                },
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRecording) {
                        val mm = elapsedSec / 60
                        val ss = elapsedSec % 60
                        "Остановить запись · Stop (%02d:%02d)".format(mm, ss)
                    } else {
                        "Начать запись · Start Zero-Lag Recording"
                    }
                )
            }
            if (!lastSaved.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Saved: $lastSaved (Movies/FableRecorder)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        ListDivider()

        SettingRow(
            title = "Разрешение · Resolution",
            subtitle = "Увеличение разрешения делает картинку более четкой, но увеличивает размер файла",
            icon = Icons.Outlined.Videocam,
            trailing = {
                Text(
                    s.recResolution.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { activeDialog = RecorderDialogType.RESOLUTION }
        )
        SettingRow(
            title = "Качество видео · Video quality",
            subtitle = "Чем выше качество, тем больше размер файла",
            trailing = {
                Text(
                    s.recBitrate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { activeDialog = RecorderDialogType.BITRATE }
        )
        SettingRow(
            title = "Ориентация · Orientation",
            subtitle = "Ориентация записываемых видео",
            trailing = {
                Text(
                    s.recOrientation.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { activeDialog = RecorderDialogType.ORIENTATION }
        )
        SettingRow(
            title = "Источник звука · Sound source",
            subtitle = "Изменение источника звука недоступно во время записи",
            trailing = {
                Text(
                    s.recAudioSource.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { if (!isRecording) activeDialog = RecorderDialogType.AUDIO_SOURCE }
        )
        SettingRow(
            title = "Частота кадров · Frame rate",
            subtitle = "Увеличение частоты кадров уменьшает размытие в движении (до 120fps)",
            trailing = {
                Text(
                    s.recFrameRate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { activeDialog = RecorderDialogType.FRAME_RATE }
        )
        ListDivider()

        SectionHeader("Опции записи · Recording options")
        SwitchRow(
            title = "Запись поверхности Blink без потери FPS (Zero-Lag Mode)",
            subtitle = "Захватывает только игровую поверхность Blink/WebView напрямую через аппаратный кодек на малых ядрах CPU (0..1), сохраняя 100% больших ядер Cortex-A75 для Bloxd.io",
            checked = s.recZeroLagBlinkMode,
            onCheckedChange = viewModel::setRecZeroLagBlinkMode
        )
        SwitchRow(
            title = "Заканчивать запись, когда экран заблокирован",
            subtitle = "Сохранять запись при блокировке устройства (End recording when screen is locked)",
            checked = s.recStopOnScreenLock,
            onCheckedChange = viewModel::setRecStopOnScreenLock
        )
        SwitchRow(
            title = "Показывать касания",
            subtitle = "Добавлять визуальный индикатор для касаний и жестов (Show touches)",
            checked = s.recShowTouches,
            onCheckedChange = viewModel::setRecShowTouches
        )
        SwitchRow(
            title = "Показывать нажатия кнопок",
            subtitle = "Отображать визуальную подсказку при нажатии на кнопки Назад, Домой или Меню (Show button presses)",
            checked = s.recShowButtonPresses,
            onCheckedChange = viewModel::setRecShowButtonPresses
        )
        ListDivider()

        SectionHeader("Другие · Other")
        SettingRow(
            title = "Настройки конфиденциальности · Privacy settings",
            subtitle = "GeckoView privacy shield, adblock & local-IP hiding during recording",
            icon = Icons.Outlined.Security,
            onClick = onOpenPrivacy
        )
    }

    when (activeDialog) {
        RecorderDialogType.RESOLUTION -> {
            PickerDialog(
                title = "Разрешение · Resolution",
                options = RecResolution.entries,
                selected = s.recResolution,
                label = { it.label },
                onSelect = {
                    viewModel.setRecResolution(it)
                    activeDialog = RecorderDialogType.NONE
                },
                onDismiss = { activeDialog = RecorderDialogType.NONE }
            )
        }
        RecorderDialogType.BITRATE -> {
            PickerDialog(
                title = "Качество видео · Video quality",
                options = RecBitrate.entries,
                selected = s.recBitrate,
                label = { it.label },
                onSelect = {
                    viewModel.setRecBitrate(it)
                    activeDialog = RecorderDialogType.NONE
                },
                onDismiss = { activeDialog = RecorderDialogType.NONE }
            )
        }
        RecorderDialogType.ORIENTATION -> {
            PickerDialog(
                title = "Ориентация · Orientation",
                options = RecOrientation.entries,
                selected = s.recOrientation,
                label = { it.label },
                onSelect = {
                    viewModel.setRecOrientation(it)
                    activeDialog = RecorderDialogType.NONE
                },
                onDismiss = { activeDialog = RecorderDialogType.NONE }
            )
        }
        RecorderDialogType.AUDIO_SOURCE -> {
            PickerDialog(
                title = "Источник звука · Sound source",
                options = RecAudioSource.entries,
                selected = s.recAudioSource,
                label = { it.label },
                onSelect = {
                    viewModel.setRecAudioSource(it)
                    activeDialog = RecorderDialogType.NONE
                },
                onDismiss = { activeDialog = RecorderDialogType.NONE }
            )
        }
        RecorderDialogType.FRAME_RATE -> {
            PickerDialog(
                title = "Частота кадров · Frame rate",
                options = RecFrameRate.entries,
                selected = s.recFrameRate,
                label = { it.label },
                onSelect = {
                    viewModel.setRecFrameRate(it)
                    activeDialog = RecorderDialogType.NONE
                },
                onDismiss = { activeDialog = RecorderDialogType.NONE }
            )
        }
        RecorderDialogType.NONE -> Unit
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = item == selected, onClick = { onSelect(item) })
                        Text(label(item), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена · Cancel")
            }
        }
    )
}
