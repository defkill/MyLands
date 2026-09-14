package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.TrackEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded tracks screen. Lists every saved track with the ability to show it on the map,
 * rename, delete and share it, and carries the record start/stop control at the top so the
 * recording button leads here instead of being a blind toggle.
 */
@Composable
fun TracksSheet(
    tracks: List<TrackEntity>,
    visibleTrackIds: Set<Long>,
    isRecording: Boolean,
    showRawTracks: Boolean,
    serviceRunning: Boolean,
    servicePointCount: Int,
    hasWakeUpStepSensor: Boolean,
    hasActivityPermission: Boolean,
    onToggleRawTracks: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleVisibility: (TrackEntity) -> Unit,
    onCenterOnTrack: (TrackEntity) -> Unit,
    onRename: (TrackEntity, String) -> Unit,
    onDelete: (TrackEntity) -> Unit,
    onShare: (TrackEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renamingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var confirmDeleteTrack by remember { mutableStateOf<TrackEntity?>(null) }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tracks_sheet"),
        color = Color(0xFF10151C),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ЗАПИСАННЫЕ ТРЕКИ",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    fontSize = 14.sp
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Record control
            Button(
                onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                modifier = Modifier.fillMaxWidth().testTag("tracks_record_toggle"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFC62828) else Color(0xFF2E7D32)
                )
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isRecording) "Остановить запись" else "Начать запись трека",
                    fontWeight = FontWeight.Bold
                )
            }

            // Recording diagnostics: which component is actually writing points, and whether
            // this device can count steps while asleep. Both decide whether a track survives
            // with the phone locked in a pocket.
            if (isRecording) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFF17212B), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            text = if (serviceRunning) {
                                "Пишет фоновая служба · точек: $servicePointCount"
                            } else {
                                "Пишет приложение (служба не запущена)"
                            },
                            color = if (serviceRunning) Color(0xFF81C784) else Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                !hasActivityPermission ->
                                    "Нет разрешения на распознавание активности — шаги не считаются, трек без GPS писаться не будет"
                                hasWakeUpStepSensor ->
                                    "Датчик шагов будит процессор — запись идёт с выключенным экраном"
                                else ->
                                    "Нет пробуждающего датчика шагов — процессор удерживается включённым, расход батареи выше"
                            },
                            color = if (!hasActivityPermission) Color(0xFFEF9A9A) else Color(0xFF78909C),
                            fontSize = 10.sp
                        )
                        if (!serviceRunning) {
                            Text(
                                text = "С заблокированным экраном запись может прерваться",
                                color = Color(0xFFEF9A9A),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // GPS outlier filtering. Filtering is display-only: the raw recording is never
            // modified, so this switch can always show exactly what the receiver reported.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (showRawTracks) "Сырой трек (без фильтра)" else "Фильтр выбросов GPS",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (showRawTracks) {
                            "Показаны все точки, включая скачки приёмника"
                        } else {
                            "Скачки GPS скрыты, расстояние считается по очищенному пути"
                        },
                        color = Color(0xFF78909C),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = !showRawTracks,
                    onCheckedChange = { onToggleRawTracks() },
                    modifier = Modifier.testTag("raw_tracks_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF69F0AE),
                        checkedTrackColor = Color(0xFF1B5E20)
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (tracks.isEmpty()) {
                Text(
                    "Пока нет записанных треков",
                    color = Color(0xFF607D8B),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(tracks) { track ->
                        val isVisible = visibleTrackIds.contains(track.id)

                        Surface(
                            color = if (isVisible) Color(0xFF162A22) else Color(0xFF182029),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onCenterOnTrack(track) }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = track.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        if (track.isActive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "● ЗАПИСЬ",
                                                color = Color(0xFFEF5350),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = dateFmt.format(Date(track.startTime)),
                                        color = Color(0xFF78909C),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.2f км%s",
                                            track.totalDistanceMeters / 1000.0,
                                            if (track.hasDeadReckoningSegments) " · есть участки без GPS" else ""
                                        ),
                                        color = Color(0xFF81C784),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                IconButton(
                                    onClick = { onToggleVisibility(track) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Показать на карте",
                                        tint = if (isVisible) Color(0xFF69F0AE) else Color(0xFF546E7A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { renamingTrack = track },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Переименовать",
                                        tint = Color(0xFF90CAF9),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onShare(track) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Отправить",
                                        tint = Color(0xFFFFD54F),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { confirmDeleteTrack = track },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = Color(0xFFEF9A9A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    renamingTrack?.let { track ->
        var newName by remember(track.id) { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renamingTrack = null },
            containerColor = Color(0xFF161E28),
            title = { Text("Переименовать трек", color = Color.White, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("track_rename_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) onRename(track, newName.trim())
                        renamingTrack = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTrack = null }) { Text("Отмена", color = Color.Gray) }
            }
        )
    }

    // Delete confirmation — recorded tracks cannot be recovered, so never delete on one tap.
    confirmDeleteTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { confirmDeleteTrack = null },
            containerColor = Color(0xFF161E28),
            title = { Text("Удалить трек?", color = Color.White, fontSize = 15.sp) },
            text = {
                Text(
                    "Трек «${track.name}» и все его точки будут удалены безвозвратно.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(track)
                        confirmDeleteTrack = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTrack = null }) { Text("Отмена", color = Color.Gray) }
            }
        )
    }
}
