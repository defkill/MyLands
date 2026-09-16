package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.geodesy.GaussKrugerConverter
import com.example.geodesy.GeodesyEngine
import com.example.geodesy.MgrsConverter
import com.example.model.CoordinateBundle
import com.example.model.CoordinateSystem
import com.example.model.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateModalSheet(
    bundle: CoordinateBundle,
    selectedSystem: CoordinateSystem,
    onSystemSelected: (CoordinateSystem) -> Unit,
    onJumpToPoint: (GeoPoint) -> Unit,
    /** Shows the current point as a QR code for hand-off with every radio off. */
    onShowQr: () -> Unit,
    /** Opens the scanner to receive a point from another device's screen. */
    onScanQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(0) } // 0: All Coordinates, 1: Coordinate Jump / Input

    // Input state for coordinate jump
    var inputSystem by remember { mutableStateOf(CoordinateSystem.MGRS) }
    var inputString by remember { mutableStateOf("") }
    var inputGkX by remember { mutableStateOf("") }
    var inputGkY by remember { mutableStateOf("") }
    var inputGkZone by remember { mutableStateOf("") }
    var inputUskX by remember { mutableStateOf("") }
    var inputUskY by remember { mutableStateOf("") }
    var inputUskZone by remember { mutableStateOf("") }
    var inputUskString by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF161C24),
        contentColor = Color.White,
        modifier = Modifier.testTag("coordinate_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "КООРДИНАТНАЯ КАРТОЧКА",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    // Lets the title shrink rather than push the actions off the row.
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShowQr,
                        modifier = Modifier.testTag("show_point_qr_button")
                    ) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = "Показать QR-код",
                            tint = Color(0xFF81C784)
                        )
                    }

                    IconButton(
                        onClick = onScanQr,
                        modifier = Modifier.testTag("scan_point_qr_button")
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "Сканировать QR-код",
                            tint = Color(0xFF00E5FF)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                    }
                }
            }

            // Tab Selector
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = Color(0xFF1E2631),
                contentColor = Color(0xFF81C784),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("Все СК точки", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Переход по координатам", fontWeight = FontWeight.SemiBold) }
                )
            }

            if (activeTab == 0) {
                // List of all coordinate representations
                CoordinateCardItem(
                    title = "MGRS (NATO / Military Grid)",
                    value = bundle.mgrs,
                    isSelected = selectedSystem == CoordinateSystem.MGRS,
                    onSelect = { onSystemSelected(CoordinateSystem.MGRS) },
                    onCopy = { copyToClipboard(context, "MGRS", bundle.mgrs) }
                )

                CoordinateCardItem(
                    title = "СК-42 / Гаусс-Крюгер (6°)",
                    value = bundle.gaussKruger,
                    isSelected = selectedSystem == CoordinateSystem.GAUSS_KRUGER,
                    onSelect = { onSystemSelected(CoordinateSystem.GAUSS_KRUGER) },
                    onCopy = { copyToClipboard(context, "СК-42 / Гаусс-Крюгер", bundle.gaussKruger) }
                )

                CoordinateCardItem(
                    title = "УСК-2000 (Гаусс-Крюгер 3°)",
                    value = bundle.usk2000,
                    isSelected = selectedSystem == CoordinateSystem.USK_2000,
                    onSelect = { onSystemSelected(CoordinateSystem.USK_2000) },
                    onCopy = { copyToClipboard(context, "УСК-2000", bundle.usk2000) }
                )

                CoordinateCardItem(
                    title = "WGS-84 Градусы/Минуты/Секунды (DMS)",
                    value = bundle.wgs84Dms,
                    isSelected = selectedSystem == CoordinateSystem.WGS84_DMS,
                    onSelect = { onSystemSelected(CoordinateSystem.WGS84_DMS) },
                    onCopy = { copyToClipboard(context, "WGS-84 DMS", bundle.wgs84Dms) }
                )

                CoordinateCardItem(
                    title = "WGS-84 Десятичные градусы (Dec)",
                    value = bundle.wgs84Decimal,
                    isSelected = selectedSystem == CoordinateSystem.WGS84_DECIMAL,
                    onSelect = { onSystemSelected(CoordinateSystem.WGS84_DECIMAL) },
                    onCopy = { copyToClipboard(context, "WGS-84", bundle.wgs84Decimal) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tactical Report Copy Button
                Button(
                    onClick = {
                        val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
                        val report = buildString {
                            appendLine("=== ДОНЕСЕНИЕ О ТОЧКЕ ===")
                            appendLine("ВРЕМЯ: $dateFmt")
                            appendLine("MGRS: ${bundle.mgrs}")
                            appendLine("СК-42: ${bundle.gaussKruger}")
                            appendLine("УСК-2000: ${bundle.usk2000}")
                            appendLine("WGS-84 DMS: ${bundle.wgs84Dms}")
                            appendLine("WGS-84 DEC: ${bundle.wgs84Decimal}")
                            bundle.altitudeMeters?.let { appendLine("ВЫСОТА: ${it.toInt()} м") }
                        }
                        copyToClipboard(context, "Донесение", report)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("copy_tactical_report_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Скопировать карточку (Донесение)", fontWeight = FontWeight.Bold)
                }

            } else {
                // Coordinate Jump / Input Tab
                Text(
                    text = "Выберите систему и введите координаты цели:",
                    color = Color(0xFFB0BEC5),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = inputSystem == CoordinateSystem.MGRS,
                        onClick = { inputSystem = CoordinateSystem.MGRS },
                        label = { Text("MGRS", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = inputSystem == CoordinateSystem.GAUSS_KRUGER,
                        onClick = { inputSystem = CoordinateSystem.GAUSS_KRUGER },
                        label = { Text("СК-42", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = inputSystem == CoordinateSystem.USK_2000,
                        onClick = { inputSystem = CoordinateSystem.USK_2000 },
                        label = { Text("УСК-2000", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = inputSystem == CoordinateSystem.WGS84_DECIMAL,
                        onClick = { inputSystem = CoordinateSystem.WGS84_DECIMAL },
                        label = { Text("WGS-84", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (inputSystem) {
                    CoordinateSystem.MGRS -> {
                        OutlinedTextField(
                            value = inputString,
                            onValueChange = { inputString = it; inputError = null },
                            label = { Text("Координата MGRS (например: 36U UA 24182 91607)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF81C784),
                                unfocusedBorderColor = Color(0xFF546E7A)
                            )
                        )
                    }
                    CoordinateSystem.GAUSS_KRUGER -> {
                        OutlinedTextField(
                            value = inputGkX,
                            onValueChange = { inputGkX = it; inputError = null },
                            label = { Text("Северное смещение X (метров, e.g. 5593124)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputGkY,
                            onValueChange = { inputGkY = it; inputError = null },
                            label = { Text("Восточное смещение Y (e.g. 6324512 или 324512)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputGkZone,
                            onValueChange = { inputGkZone = it; inputError = null },
                            label = { Text("Номер зоны 6° (если Y без префикса, например 6)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    CoordinateSystem.USK_2000 -> {
                        OutlinedTextField(
                            value = inputUskX,
                            onValueChange = { inputUskX = it; inputError = null },
                            label = { Text("Северное смещение X (метров, e.g. 5593124)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputUskY,
                            onValueChange = { inputUskY = it; inputError = null },
                            label = { Text("Восточное смещение Y (e.g. 10324512 или 324512)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputUskZone,
                            onValueChange = { inputUskZone = it; inputError = null },
                            label = { Text("Номер зоны 3° (если Y без префикса, например 10)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputUskString,
                            onValueChange = {
                                inputUskString = it
                                inputError = null
                                parseUskLine(it)?.let { parsed ->
                                    inputUskX = parsed.first.toLong().toString()
                                    inputUskY = parsed.second.toLong().toString()
                                    if (parsed.third != null) inputUskZone = parsed.third.toString()
                                }
                            },
                            label = { Text("Или вставьте строкой (X Y [Зона] / 5593124 10324512)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    CoordinateSystem.WGS84_DECIMAL -> {
                        OutlinedTextField(
                            value = inputString,
                            onValueChange = { inputString = it; inputError = null },
                            label = { Text("Широта, Долгота (например: 50.4501, 30.5234)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {}
                }

                inputError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = err, color = Color(0xFFEF5350), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        try {
                            when (inputSystem) {
                                CoordinateSystem.MGRS -> {
                                    val (lat, lon) = MgrsConverter.inverse(inputString.trim())
                                    onJumpToPoint(GeoPoint(lat, lon))
                                    onDismiss()
                                }
                                CoordinateSystem.GAUSS_KRUGER -> {
                                    val x = inputGkX.trim().toDouble()
                                    val y = inputGkY.trim().toDouble()
                                    val zone = inputGkZone.trim().toIntOrNull()
                                    val (skLat, skLon) = GaussKrugerConverter.inverse(x, y, zoneInput = zone)
                                    val (wgsLat, wgsLon, _) = com.example.geodesy.DatumTransform.sk42ToWgs84(skLat, skLon)
                                    onJumpToPoint(GeoPoint(wgsLat, wgsLon))
                                    onDismiss()
                                }
                                CoordinateSystem.USK_2000 -> {
                                    var x = inputUskX.trim().toDoubleOrNull()
                                    var y = inputUskY.trim().toDoubleOrNull()
                                    var zone = inputUskZone.trim().toIntOrNull()

                                    if (x == null || y == null) {
                                        val parsed = parseUskLine(inputUskString)
                                        if (parsed != null) {
                                            x = parsed.first
                                            y = parsed.second
                                            zone = parsed.third ?: zone
                                        }
                                    }

                                    if (x != null && y != null) {
                                        val geoPoint = GeodesyEngine.usk2000ToWgs84(x, y, zone)
                                        onJumpToPoint(geoPoint)
                                        onDismiss()
                                    } else {
                                        inputError = "Введите X и Y УСК-2000 (или вставьте строку)"
                                    }
                                }
                                CoordinateSystem.WGS84_DECIMAL -> {
                                    val parts = inputString.split("[,;\\s]+".toRegex()).filter { it.isNotBlank() }
                                    if (parts.size >= 2) {
                                        val lat = parts[0].toDouble()
                                        val lon = parts[1].toDouble()
                                        onJumpToPoint(GeoPoint(lat, lon))
                                        onDismiss()
                                    } else {
                                        inputError = "Введите два числа: широту и долготу"
                                    }
                                }
                                else -> {}
                            }
                        } catch (e: Exception) {
                            inputError = "Ошибка распознавания: ${e.message}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Перейти к точке на карте", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun parseUskLine(text: String): Triple<Double, Double, Int?>? {
    if (text.isBlank()) return null
    val clean = text.replace("X", " ", ignoreCase = true)
        .replace("Y", " ", ignoreCase = true)
        .replace("Зона", " ", ignoreCase = true)
        .replace("Zone", " ", ignoreCase = true)
        .replace("=", " ")
        .replace(":", " ")
        .replace(";", " ")
        .replace(",", " ")
    val parts = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (parts.size >= 2) {
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        val zone = if (parts.size >= 3) parts[2].toIntOrNull() else null
        return Triple(x, y, zone)
    }
    return null
}

@Composable
private fun CoordinateCardItem(
    title: String,
    value: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onSelect),
        color = if (isSelected) Color(0xFF263238) else Color(0xFF1B222C),
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF81C784)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isSelected) Color(0xFF81C784) else Color(0xFF90A4AE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Скопировать",
                    tint = Color(0xFFB0BEC5),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "$label скопирован в буфер", Toast.LENGTH_SHORT).show()
}
