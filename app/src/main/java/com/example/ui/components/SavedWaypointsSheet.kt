package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.example.data.entity.WaypointEntity
import com.example.geodesy.GeodesyEngine
import com.example.model.AngleUnit
import com.example.model.CoordinateSystem
import com.example.model.GeoPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedWaypointsSheet(
    waypoints: List<WaypointEntity>,
    userLocation: GeoPoint?,
    angleUnit: AngleUnit,
    coordinateSystem: CoordinateSystem,
    onSelectWaypoint: (WaypointEntity) -> Unit,
    onDeleteWaypoint: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var confirmDeleteWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }

    val filteredWaypoints = remember(waypoints, searchQuery) {
        if (searchQuery.isBlank()) {
            waypoints
        } else {
            val q = searchQuery.trim().lowercase()
            waypoints.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF161E28),
        contentColor = Color.White,
        modifier = modifier.testTag("saved_waypoints_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ТОЧКИ (${filteredWaypoints.size}/${waypoints.size})",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search TextField
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("waypoint_search_field"),
                placeholder = { Text("Поиск по названию или описанию...", color = Color(0xFF78909C), fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF90A4AE))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF37474F),
                    focusedContainerColor = Color(0xFF1E2836),
                    unfocusedContainerColor = Color(0xFF1E2836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredWaypoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (waypoints.isEmpty()) "Список точек пуст" else "Ничего не найдено",
                        color = Color(0xFF78909C),
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredWaypoints, key = { it.id }) { wp ->
                        val wpGeo = wp.toGeoPoint()
                        val distanceMeters = userLocation?.let { GeodesyEngine.distanceMeters(it, wpGeo) }
                        val azimuthDeg = userLocation?.let { GeodesyEngine.azimuthDegrees(it, wpGeo) }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("waypoint_item_${wp.id}")
                                .clickable {
                                    onSelectWaypoint(wp)
                                    onDismiss()
                                },
                            color = Color(0xFF1E2632),
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Color circle
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(wp.colorArgb), shape = CircleShape)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = wp.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )

                                    if (wp.description.isNotBlank()) {
                                        Text(
                                            text = wp.description,
                                            color = Color(0xFFB0BEC5),
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }

                                    // Formatted coordinates preview
                                    val coordText = remember(wp.latitude, wp.longitude, coordinateSystem) {
                                        val bundle = GeodesyEngine.getCoordinateBundle(wp.latitude, wp.longitude)
                                        when (coordinateSystem) {
                                            CoordinateSystem.MGRS -> bundle.mgrs
                                            CoordinateSystem.GAUSS_KRUGER -> bundle.gaussKruger
                                            CoordinateSystem.USK_2000 -> bundle.usk2000
                                            else -> "${String.format(java.util.Locale.US, "%.5f°", wp.latitude)}, ${String.format(java.util.Locale.US, "%.5f°", wp.longitude)}"
                                        }
                                    }
                                    Text(
                                        text = coordText,
                                        color = Color(0xFF78909C),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                // Range & Azimuth pill from current user position
                                if (distanceMeters != null && azimuthDeg != null) {
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    ) {
                                        val distStr = if (distanceMeters >= 1000) {
                                            String.format(java.util.Locale.US, "%.2f км", distanceMeters / 1000.0)
                                        } else {
                                            String.format(java.util.Locale.US, "%.0f м", distanceMeters)
                                        }
                                        Text(
                                            text = distStr,
                                            color = Color(0xFF81C784),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = AngleUnit.format(azimuthDeg, angleUnit),
                                            color = Color(0xFFFFD54F),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                // Delete icon
                                IconButton(
                                    onClick = { confirmDeleteWaypoint = wp },
                                    modifier = Modifier.size(32.dp).testTag("delete_waypoint_btn_${wp.id}")
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Удалить точку",
                                        tint = Color(0xFFEF5350),
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

    // Delete confirmation — waypoints cannot be recovered, so never delete on one tap.
    confirmDeleteWaypoint?.let { wp ->
        AlertDialog(
            onDismissRequest = { confirmDeleteWaypoint = null },
            containerColor = Color(0xFF161E28),
            title = { Text("Удалить точку?", color = Color.White, fontSize = 15.sp) },
            text = {
                Text(
                    "Точка «${wp.name}» будет удалена безвозвратно.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteWaypoint(wp.id)
                        confirmDeleteWaypoint = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteWaypoint = null }) { Text("Отмена", color = Color.Gray) }
            }
        )
    }
}
