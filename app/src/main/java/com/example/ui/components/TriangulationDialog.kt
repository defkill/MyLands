package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.WaypointEntity
import com.example.geodesy.GeodesyEngine
import com.example.model.AzimuthRay
import com.example.model.IntersectionResult
import com.example.model.TriangulationState

/**
 * Triangulation tool: cast a ray (or fixed-length segment) from a chosen waypoint along an
 * azimuth. With two rays the crossing point is computed and can be saved as a new waypoint.
 */
@Composable
fun TriangulationDialog(
    state: TriangulationState,
    allWaypoints: List<WaypointEntity>,
    preselected: WaypointEntity?,
    onAddRay: (WaypointEntity, Double, Double?) -> Unit,
    onRemoveRay: (Int) -> Unit,
    onSaveIntersection: (String) -> Unit,
    onSaveRayEnd: (AzimuthRay, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedWp by remember(preselected) { mutableStateOf(preselected ?: allWaypoints.firstOrNull()) }
    var azimuthText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var pointName by remember { mutableStateOf("Цель") }
    var wpMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ТРИАНГУЛЯЦИЯ", color = Color(0xFFFF7043), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                Text(
                    "Выберите точку, задайте азимут и (необязательно) дальность. " +
                        "Два луча дадут точку пересечения.",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Origin waypoint picker
                Box {
                    OutlinedButton(
                        onClick = { wpMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().testTag("triangulation_origin_button")
                    ) {
                        Text(selectedWp?.name ?: "Нет точек", color = Color.White, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = wpMenuOpen,
                        onDismissRequest = { wpMenuOpen = false },
                        containerColor = Color(0xFF1E2632)
                    ) {
                        allWaypoints.forEach { wp ->
                            DropdownMenuItem(
                                text = { Text(wp.name, color = Color.White) },
                                onClick = {
                                    selectedWp = wp
                                    wpMenuOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = azimuthText,
                        onValueChange = { azimuthText = it },
                        label = { Text("Азимут °") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("triangulation_azimuth_field")
                    )
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { distanceText = it },
                        label = { Text("Дальность м") },
                        placeholder = { Text("луч") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("triangulation_distance_field")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val wp = selectedWp ?: return@Button
                        val az = azimuthText.replace(',', '.').toDoubleOrNull() ?: return@Button
                        val dist = distanceText.replace(',', '.').toDoubleOrNull()
                        onAddRay(wp, ((az % 360) + 360) % 360, dist)
                        azimuthText = ""
                        distanceText = ""
                    },
                    enabled = selectedWp != null && azimuthText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("triangulation_add_ray_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043))
                ) {
                    Text("Добавить луч", fontWeight = FontWeight.Bold)
                }

                if (state.rays.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("АКТИВНЫЕ ЛУЧИ", color = Color(0xFF90A4AE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))

                    state.rays.forEachIndexed { index, ray ->
                        Surface(
                            color = Color(0xFF1E2632),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ray.origin.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (ray.lengthMeters != null)
                                            "Аз ${"%.1f".format(ray.azimuthDeg)}° · ${"%.0f".format(ray.lengthMeters)} м"
                                        else "Аз ${"%.1f".format(ray.azimuthDeg)}° · открытый луч",
                                        color = Color(0xFFFFB74D),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (ray.lengthMeters != null) {
                                    TextButton(onClick = { onSaveRayEnd(ray, "${ray.origin.name}+") }) {
                                        Text("Конец", color = Color(0xFF81C784), fontSize = 11.sp)
                                    }
                                }
                                IconButton(onClick = { onRemoveRay(index) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить луч",
                                        tint = Color(0xFFEF9A9A),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Intersection outcome
                when (val res = state.result) {
                    is IntersectionResult.Success -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(color = Color(0xFF12301F), shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(10.dp).fillMaxWidth()) {
                                Text("ТОЧКА ПЕРЕСЕЧЕНИЯ", color = Color(0xFF69F0AE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                val b = GeodesyEngine.getCoordinateBundle(
                                    res.intersectionPoint.latitude,
                                    res.intersectionPoint.longitude
                                )
                                Text(b.mgrs, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    text = "От 1: ${"%.0f".format(res.distance1Meters)} м · От 2: ${"%.0f".format(res.distance2Meters)} м · Угол ${"%.1f".format(res.angleBetweenRaysDeg)}°",
                                    color = Color(0xFF81C784),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = pointName,
                                    onValueChange = { pointName = it },
                                    label = { Text("Имя точки") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    is IntersectionResult.RaysParallel ->
                        WarnText(res.message)
                    is IntersectionResult.RaysDiverge ->
                        WarnText(res.message)
                    is IntersectionResult.Error ->
                        WarnText(res.message)
                    null -> {}
                }
            }
        },
        confirmButton = {
            if (state.result is IntersectionResult.Success) {
                Button(
                    onClick = { onSaveIntersection(pointName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier.testTag("triangulation_save_button")
                ) {
                    Text("Создать точку", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.Gray) }
        }
    )
}

@Composable
private fun WarnText(msg: String) {
    Spacer(modifier = Modifier.height(10.dp))
    Text(msg, color = Color(0xFFFFB74D), fontSize = 12.sp)
}

/**
 * Rename / re-describe an existing waypoint.
 */
@Composable
fun EditWaypointDialog(
    waypoint: WaypointEntity,
    onSave: (String, String) -> Unit,
    onStartTriangulation: () -> Unit,
    onShowQr: () -> Unit,
    onCheckVisibility: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(waypoint.id) { mutableStateOf(waypoint.name) }
    var description by remember(waypoint.id) { mutableStateOf(waypoint.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = { Text("РЕДАКТИРОВАНИЕ ТОЧКИ", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_waypoint_name_field")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onStartTriangulation,
                    modifier = Modifier.fillMaxWidth().testTag("edit_waypoint_ray_button")
                ) {
                    Text("Пустить луч / отрезок от точки", color = Color(0xFFFF7043))
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onCheckVisibility,
                    modifier = Modifier.fillMaxWidth().testTag("edit_waypoint_los_button")
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color(0xFF80DEEA),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Проверить видимость отсюда", color = Color(0xFF80DEEA))
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onShowQr,
                    modifier = Modifier.fillMaxWidth().testTag("edit_waypoint_qr_button")
                ) {
                    Text("Показать QR-код", color = Color(0xFF81C784))
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить точку", color = Color(0xFFEF9A9A))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                modifier = Modifier.testTag("edit_waypoint_save_button")
            ) {
                Text("Сохранить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
        }
    )
}
