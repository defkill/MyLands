package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.data.entity.RouteEntity
import com.example.data.entity.WaypointEntity
import com.example.model.*
import java.util.Locale

@Composable
fun RulerOverlay(
    rulerState: RulerState,
    angleUnit: AngleUnit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "ЛИНЕЙКА (ДАЛЬНОСТЬ И АЗИМУТ)",
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Straighten,
    iconTint: Color = Color(0xFFFFD54F),
    onReverseAzimuthClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("ruler_overlay"),
        color = Color(0xFF1E2833),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("РАССТОЯНИЕ", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = rulerState.formatDistance(),
                        color = Color(0xFF81C784),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val unitLabel = if (angleUnit == AngleUnit.DEGREES_360) "АЗИМУТ (360°)" else "АЗИМУТ (60-00)"
                    Text(unitLabel, color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = rulerState.formatAzimuth(angleUnit),
                        color = Color(0xFFFFD54F),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ОБРАТНЫЙ АЗ.", color = Color(0xFF90A4AE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (onReverseAzimuthClick != null) {
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = onReverseAzimuthClick,
                                modifier = Modifier.size(22.dp).testTag("reverse_azimuth_button")
                            ) {
                                Icon(
                                    Icons.Default.SwapCalls,
                                    contentDescription = "Обратный азимут",
                                    tint = Color(0xFFFF8A65),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = rulerState.formatReverseAzimuth(angleUnit),
                        color = Color(0xFFFF8A65),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun RouteBuilderPanel(
    routeState: RouteBuilderState,
    allWaypoints: List<WaypointEntity>,
    savedRoutes: List<RouteEntity>,
    angleUnit: AngleUnit,
    onToggleWaypoint: (WaypointEntity) -> Unit,
    onSaveRoute: () -> Unit,
    onLoadRoute: (RouteEntity) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("route_builder_panel"),
        color = Color(0xFF161E28),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ПОСТРОЕНИЕ МАРШРУТА ПО ТОЧКАМ", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 14.sp)
                    Text(
                        text = "Выбрано точек: ${routeState.selectedWaypoints.size} | Длина: ${routeState.formatTotalDistance()}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Отмена", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Waypoints in route list
            if (routeState.legs.isNotEmpty()) {
                Text("Участки маршрута:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                    items(routeState.legs) { leg ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = Color(0xFF1E2632),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${leg.index}. ${leg.fromPoint.name} → ${leg.toPoint.name}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${leg.formatDistance()} | Аз: ${AngleUnit.format(leg.forwardAzimuthDeg, angleUnit)}",
                                    color = Color(0xFF81C784),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Выберите как минимум 2 точки из списка или нажмите на них на карте:",
                    color = Color(0xFF78909C),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable chips of ALL available waypoints. Included points are highlighted
            // green so it is obvious at a glance which ones are part of the route.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(allWaypoints) { wp ->
                    val isIncluded = routeState.selectedWaypoints.any { it.id == wp.id }
                    val orderIndex = routeState.selectedWaypoints.indexOfFirst { it.id == wp.id }
                    FilterChip(
                        selected = isIncluded,
                        onClick = { onToggleWaypoint(wp) },
                        label = {
                            Text(
                                text = if (isIncluded) "${orderIndex + 1}. ${wp.name}" else wp.name,
                                maxLines = 1,
                                fontWeight = if (isIncluded) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2E7D32),
                            selectedLabelColor = Color(0xFFB9F6CA),
                            containerColor = Color(0xFF1E2836),
                            labelColor = Color(0xFFB0BEC5)
                        )
                    )
                }
            }

            // Previously saved routes, listed right below the buttons so a saved route
            // is immediately visible instead of silently disappearing into the database.
            if (savedRoutes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "СОХРАНЁННЫЕ МАРШРУТЫ (${savedRoutes.size})",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(savedRoutes) { route ->
                        Surface(
                            color = Color(0xFF1B3A2A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { onLoadRoute(route) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = route.name,
                                    color = Color(0xFFB9F6CA),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = String.format(
                                        java.util.Locale.US,
                                        "%.2f км · %d точек",
                                        route.totalDistanceMeters / 1000.0,
                                        route.parseWaypointIds().size
                                    ),
                                    color = Color(0xFF81C784),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Отмена")
                }
                Button(
                    onClick = onSaveRoute,
                    enabled = routeState.selectedWaypoints.size >= 2,
                    modifier = Modifier.weight(1f).testTag("save_route_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AddWaypointDialog(
    initialPoint: GeoPoint,
    onSave: (name: String, description: String, color: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("Точка ${System.currentTimeMillis() % 1000}") }
    var description by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(0xFFFF5722.toInt()) }

    val colors = listOf(
        0xFFFF5722.toInt(), // Orange
        0xFF4CAF50.toInt(), // Green
        0xFF2196F3.toInt(), // Blue
        0xFFE91E63.toInt(), // Pink
        0xFFFFD600.toInt()  // Yellow
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("СОХРАНИТЬ ТОЧКУ (WAYPOINT)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название точки") },
                    modifier = Modifier.fillMaxWidth().testTag("waypoint_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание / ориентир (опционально)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Цвет маркера:", color = Color.LightGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { col ->
                        Surface(
                            onClick = { selectedColor = col },
                            modifier = Modifier.size(36.dp),
                            color = Color(col),
                            shape = RoundedCornerShape(18.dp),
                            border = if (selectedColor == col) androidx.compose.foundation.BorderStroke(3.dp, Color.White) else null
                        ) {}
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description, selectedColor) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.testTag("save_waypoint_button")
            ) {
                Text("Сохранить точку", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = Color(0xFF161C24)
    )
}
