package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.qr.QrPayload
import com.example.geodesy.GeodesyEngine
import com.example.model.CoordinateSystem
import com.example.model.GeoPoint
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/**
 * Shows a waypoint as a QR code for hand-off to another device with every radio off.
 *
 * Rendered pure black on pure white with a generous quiet zone and high error correction: the
 * code has to survive being read off a scratched screen, at an angle, in bad light.
 */
@Composable
fun QrCodeDialog(
    name: String,
    point: GeoPoint,
    coordinateSystem: CoordinateSystem,
    onDismiss: () -> Unit
) {
    val payload = remember(name, point) {
        QrPayload.encodePoint(name, point.latitude, point.longitude, point.altitude)
    }

    val bitmap = remember(payload) { generateQrBitmap(payload) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f),
            color = Color(0xFF10151C),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ПЕРЕДАЧА ТОЧКИ", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (bitmap != null) {
                    // White frame doubles as the quiet zone scanners need.
                    Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR-код точки",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .padding(16.dp)
                                .testTag("qr_code_image")
                        )
                    }
                } else {
                    Text("Не удалось построить QR-код", color = Color(0xFFEF9A9A))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))

                val bundle = remember(point) {
                    GeodesyEngine.getCoordinateBundle(point.latitude, point.longitude, point.altitude)
                }
                Text(
                    text = bundle.getFormatted(coordinateSystem),
                    color = Color(0xFF81D4FA),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Наведите камеру другого устройства через «Сканер QR». " +
                        "Связь и интернет не нужны.",
                    color = Color(0xFF78909C),
                    fontSize = 11.sp
                )
                Text(
                    text = "Размер данных: ${payload.size} байт",
                    color = Color(0xFF546E7A),
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun generateQrBitmap(payload: ByteArray, size: Int = 720): Bitmap? {
    return try {
        // ISO-8859-1 maps bytes 1:1 to chars, so ZXing emits them unchanged in byte mode —
        // no Base64 inflation, which matters for keeping the QR version low.
        val content = String(payload, Charsets.ISO_8859_1)

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 2
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

/**
 * Full-screen QR scanner. CameraX for the preview, ZXing for decoding — both fully offline,
 * with no Play Services dependency and no model to download.
 */
@Composable
fun QrScannerDialog(
    onPointScanned: (QrPayload.DecodedPoint) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Guard so one code is not reported dozens of times while it stays in frame.
    var alreadyHandled by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activeCameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            cameraExecutor.shutdown()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {

                if (hasPermission) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("qr_scanner_preview"),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)

                            providerFuture.addListener({
                                try {
                                    val provider = providerFuture.get()
                                    activeCameraProvider = provider

                                    val preview = androidx.camera.core.Preview.Builder().build()
                                        .also { it.surfaceProvider = previewView.surfaceProvider }

                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    analysis.setAnalyzer(cameraExecutor) { image ->
                                        if (!alreadyHandled) {
                                            decodeFrame(image)?.let { decoded ->
                                                alreadyHandled = true
                                                previewView.post { onPointScanned(decoded) }
                                            }
                                        }
                                        image.close()
                                    }

                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis
                                    )
                                } catch (_: Exception) {
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        }
                    )

                    // Aiming frame
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val side = size.minDimension * 0.65f
                        val left = (size.width - side) / 2f
                        val top = (size.height - side) / 2f
                        drawRect(
                            color = Color(0xFF00E5FF),
                            topLeft = Offset(left, top),
                            size = Size(side, side),
                            style = Stroke(4f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Нужен доступ к камере для сканирования QR-кодов",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Разрешить")
                        }
                    }
                }

                Text(
                    "Наведите на QR-код точки",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

/** Decodes one camera frame, returning a point only for payloads in our own format. */
private fun decodeFrame(image: ImageProxy): QrPayload.DecodedPoint? {
    return try {
        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val source = PlanarYUVLuminanceSource(
            data, image.width, image.height,
            0, 0, image.width, image.height,
            false
        )

        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "ISO-8859-1"
        )

        val result = MultiFormatReader().apply { setHints(hints) }
            .decodeWithState(BinaryBitmap(HybridBinarizer(source)))

        QrPayload.decode(result.text.toByteArray(Charsets.ISO_8859_1))
    } catch (e: Exception) {
        null
    }
}

/**
 * Preview of a scanned point before anything is written to the database.
 */
@Composable
fun ScannedPointDialog(
    decoded: QrPayload.DecodedPoint,
    onSave: () -> Unit,
    onShowOnMap: () -> Unit,
    onDismiss: () -> Unit
) {
    val bundle = remember(decoded) {
        GeodesyEngine.getCoordinateBundle(decoded.latitude, decoded.longitude, decoded.altitudeMeters)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = {
            Text("ПОЛУЧЕНА ТОЧКА", color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(decoded.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(10.dp))

                listOf(
                    "WGS84" to bundle.wgs84Decimal,
                    "MGRS" to bundle.mgrs,
                    "СК-42" to bundle.gaussKruger,
                    "УСК-2000" to bundle.usk2000
                ).forEach { (label, value) ->
                    Text(label, color = Color(0xFF607D8B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        value,
                        color = Color(0xFF81D4FA),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                decoded.altitudeMeters?.let {
                    Text(
                        "Высота: ${it.toInt()} м",
                        color = Color(0xFF90A4AE),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.testTag("save_scanned_point_button")
            ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onShowOnMap) { Text("На карте", color = Color(0xFF90CAF9)) }
                TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
            }
        }
    )
}
