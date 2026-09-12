package com.example.ui.components

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
import com.example.data.settlement.Settlement
import com.example.data.settlement.SettlementRepository
import com.example.geodesy.GeodesyEngine
import com.example.model.GeoPoint
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettlementSearchSheet(
    repository: SettlementRepository,
    userLocation: GeoPoint?,
    onSelectSettlement: (Settlement) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedOblast by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Settlement>>(emptyList()) }
    var availableOblasts by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Load initial list and oblasts
    LaunchedEffect(Unit) {
        isLoading = true
        availableOblasts = repository.getOblasts()
        results = repository.search("", limit = 50)
        isLoading = false
    }

    // Reactively search on query or oblast changes
    LaunchedEffect(searchQuery, selectedOblast) {
        coroutineScope.launch {
            results = repository.search(searchQuery, selectedOblast, limit = 80)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF161E28),
        contentColor = Color.White,
        modifier = modifier.testTag("settlement_search_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title & Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationCity,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ПОИСК НАСЕЛЁННЫХ ПУНКТОВ",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }

            Text(
                text = "Датасет: assets/settlements_ua.csv (name, oblast, latitude, longitude)",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Search input field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settlement_search_field"),
                placeholder = { Text("Название (например: Олександрівка, Київ)...", color = Color(0xFF78909C), fontSize = 14.sp) },
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
                    focusedBorderColor = Color(0xFFFFB74D),
                    unfocusedBorderColor = Color(0xFF37474F),
                    focusedContainerColor = Color(0xFF1E2836),
                    unfocusedContainerColor = Color(0xFF1E2836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Oblast filter chips
            if (availableOblasts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = selectedOblast == null,
                            onClick = { selectedOblast = null },
                            label = { Text("Все области", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEF6C00),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                    items(availableOblasts) { obl ->
                        FilterChip(
                            selected = selectedOblast == obl,
                            onClick = { selectedOblast = if (selectedOblast == obl) null else obl },
                            label = { Text(obl, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEF6C00),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Results count
            Text(
                text = "Найдено: ${results.size}",
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFFFB74D))
                }
            } else if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "Введите название населённого пункта" else "Населённый пункт '$searchQuery' не найден",
                        color = Color(0xFF78909C),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { s ->
                        val distMeters = userLocation?.let {
                            GeodesyEngine.distanceMeters(it, GeoPoint(s.latitude, s.longitude))
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settlement_item_${s.name}_${s.oblast}")
                                .clickable {
                                    onSelectSettlement(s)
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = s.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Oblast Badge to resolve duplicate names
                                        Surface(
                                            color = Color(0x33FFB74D),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (s.oblast.endsWith("область", ignoreCase = true) || s.oblast.endsWith("обл.", ignoreCase = true)) s.oblast else "${s.oblast} обл.",
                                                color = Color(0xFFFFCC80),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Text(
                                        text = "${String.format(java.util.Locale.US, "%.4f° N", s.latitude)}, ${String.format(java.util.Locale.US, "%.4f° E", s.longitude)}",
                                        color = Color(0xFF90A4AE),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                if (distMeters != null) {
                                    val distStr = if (distMeters >= 1000) {
                                        String.format(java.util.Locale.US, "%.1f км", distMeters / 1000.0)
                                    } else {
                                        String.format(java.util.Locale.US, "%.0f м", distMeters)
                                    }
                                    Text(
                                        text = distStr,
                                        color = Color(0xFF81C784),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Перейти",
                                    tint = Color(0xFF78909C),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
