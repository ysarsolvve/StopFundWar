package com.ysar.stopfundwar.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.AspectRatio.RATIO_4_3
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.common.util.concurrent.ListenableFuture
import com.ysar.stopfundwar.util.*
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    Permission(
        permission = Manifest.permission.CAMERA,
        permissionNotAvailableContent = {
            Column() {
                Text("O noes! No Camera!")
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                ) {
                    Text("Open Settings")
                }
            }
        }
    )
    CameraPreview()
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_START,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
//    var sourceInfo by remember { mutableStateOf(SourceInfo(10, 10)) }
    var detectedBrands by remember { mutableStateOf<List<Recognition>>(emptyList()) }
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
            .configureCamera(
                previewView, lifecycleOwner, cameraSelector, context,
//                setSourceInfo = { sourceInfo = it },
                onBrandsDetected = { detectedBrands = it },
            )
    }

    AndroidView(
        modifier = modifier,
        factory = {
            previewView.apply {
                this.scaleType = scaleType
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Preview is incorrectly scaled in Compose on some devices without this
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            previewView

        })
    DetectedBrands(brands = detectedBrands, previewView = previewView)
}

@Composable
fun DetectedBrands(
    brands: List<Recognition>,
    previewView: PreviewView
) {
    Log.e(
        "ImageProxy",
        "DetectedBrands Start"
    )
    Log.e("CAMERA", "brands ${brands}")
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (brand in brands) {
            val scaleWidth = previewView.width.toFloat() / 640
            val scaleHeight = previewView.height.toFloat() / 640
//            drawRect(
//                Color.Gray, style = Stroke(2.dp.toPx()),
//                topLeft = Offset(brand.getLocation().left * scaleWidth, brand.getLocation().top * scaleHeight),
//                size = Size(brand.getLocation().width(), brand.getLocation().height())
//            )
            drawCircle(
                Color.Green,
                20f,
                Offset(
                    brand.getLocation().centerX() * scaleWidth,
                    brand.getLocation().centerY() * scaleHeight
                )
            )
        }
    }
    Log.e(
        "ImageProxy",
        "DetectedBrands Finished"
    )
}

private fun ListenableFuture<ProcessCameraProvider>.configureCamera(
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    cameraSelector: CameraSelector,
    context: Context,
//    setSourceInfo: (SourceInfo) -> Unit,
    onBrandsDetected: (List<Recognition>) -> Unit,
): ListenableFuture<ProcessCameraProvider> {
    addListener({
        val preview = androidx.camera.core.Preview.Builder()
            .setTargetAspectRatio(RATIO_4_3)
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val analysis = bindAnalysisUseCase(onBrandsDetected, context, previewView)
        try {
            get().apply {
                unbindAll()
                bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
            }
        } catch (exc: Exception) {
            TODO("process errors")
        }
    }, ContextCompat.getMainExecutor(context))
    return this
}

private fun bindAnalysisUseCase(
//    setSourceInfo: (SourceInfo) -> Unit,
//    onFacesDetected: (List<Face>) -> Unit
    onBrandsDetected: (List<Recognition>) -> Unit,
    context: Context,
    previewView: PreviewView
): ImageAnalysis? {
    val cameraExecutor = Executors.newSingleThreadExecutor()
    val imageProcessor = try {
        ImageProcess(context)
    } catch (e: Exception) {
        Log.e("CAMERA", "Can not create image processor", e)
        return null
    }
    val builder = ImageAnalysis.Builder()
    val analysisUseCase = builder.setTargetAspectRatio(RATIO_4_3).build()

    analysisUseCase.setAnalyzer(
        cameraExecutor
    ) { imageProxy: ImageProxy ->
        try {
            imageProcessor.processImageProxy(imageProxy, onBrandsDetected, previewView)
        } catch (e: Exception) {
            Log.e(
                "CAMERA", "Failed to process image. Error: " + e.localizedMessage
            )
        }
    }
    return analysisUseCase
}

