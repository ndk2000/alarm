package com.ccsoft.alarm.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*

/**
 * QR 码扫描对话框
 * 使用 CameraX + ZXing 实时扫描二维码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerDialog(
    onScanResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scannerExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val scanResult = decodeQrFromUri(context, uri)
                if (scanResult != null) {
                    onScanResult(scanResult)
                } else {
                    android.widget.Toast.makeText(context, "未识别到二维码", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 检查/请求相机权限
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    DisposableEffect(Unit) {
        onDispose {
            scannerExecutor.shutdownNow()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Camera preview
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        android.util.Log.d("QrScanner", "Factory: Creating PreviewView")
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { previewView ->
                        android.util.Log.d("QrScanner", "Update: Binding Camera. Lifecycle: ${lifecycleOwner.lifecycle.currentState}")
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                imageAnalysis.setAnalyzer(scannerExecutor) { image ->
                                    try {
                                        scanQrCodeInImage(image)?.let { result ->
                                            android.util.Log.d("QrScanner", "QR Code detected: ${result.text}")
                                            mainHandler.post { onScanResult(result.text) }
                                        }
                                    } catch (e: Exception) {
                                        image.close()
                                    }
                                }
                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.unbindAll()
                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED)) {
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                    android.util.Log.d("QrScanner", "bindToLifecycle successful")
                                } else {
                                    android.util.Log.w("QrScanner", "Lifecycle not initialized, skipping bind")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("QrScanner", "Camera Setup Error", e)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                )
                
                // Debug UI
                Text(
                    "Scanner Active",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }

            // 扫码框指示器 + 顶部工具栏
            Box(modifier = Modifier.fillMaxSize()) {
                // 扫码框
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .border(2.dp, Color(0xFF00FF00), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "将二维码置于框内",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 顶部工具栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "扫描分享码",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        scannerExecutor.shutdownNow()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }

                // 底部工具栏 - 从相册识别
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 60.dp)
                ) {
                    Button(
                        onClick = { 
                            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            galleryLauncher.launch(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("从相册识别")
                    }
                }
            }
        }
    }
}

/**
 * 从 URI 识别二维码
 */
private fun decodeQrFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true
            )
            setHints(hints)
        }
        reader.decode(binaryBitmap).text
    } catch (e: Exception) {
        android.util.Log.e("QrScanner", "Decode from URI failed", e)
        null
    }
}

/**
 * 使用 ZXing 解码 CameraX 图像中的二维码
 */
private fun scanQrCodeInImage(imageProxy: ImageProxy): Result? {
    try {
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) return null

        android.util.Log.v("QrScanner", "Processing frame: ${imageProxy.width}x${imageProxy.height}")

        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val source = com.google.zxing.PlanarYUVLuminanceSource(
            data,
            imageProxy.width,
            imageProxy.height,
            0,
            0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true
            )
            setHints(hints)
        }
        return reader.decodeWithState(bitmap)
    } catch (e: Exception) {
        return null
    } finally {
        imageProxy.close()
    }
}
