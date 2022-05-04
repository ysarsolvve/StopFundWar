package com.ysar.stopfundwar.screen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.common.util.concurrent.ListenableFuture
import com.ysar.stopfundwar.ui.theme.OnBoardingComposeTheme
import com.ysar.stopfundwar.util.ImageProcess
import com.ysar.stopfundwar.util.Permission
import com.ysar.stopfundwar.util.Recognition
import com.ysar.stopfundwar.util.Yolov5TFLiteDetector
import java.util.concurrent.Executors

@Composable
fun CameraScreen() {
    OnBoardingComposeTheme {
        Surface(color = MaterialTheme.colors.background) {
            StartCamera()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@SuppressLint("RememberReturnType")
@Composable
fun StartCamera(
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    var sourceInfo by remember { mutableStateOf(SourceInfo(10, 10)) }
    var detectedBrands by remember { mutableStateOf<List<Recognition>>(emptyList()) }
    val yolov5TFLiteDetector = Yolov5TFLiteDetector()
    yolov5TFLiteDetector.initialModel(context)
    yolov5TFLiteDetector.addGPUDelegate()
    Permission(
        permission = Manifest.permission.CAMERA,
        permissionNotAvailableContent = {
            Column(modifier) {
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
    val cameraProviderFuture = remember(sourceInfo) {
        ProcessCameraProvider.getInstance(context)
            .configureCamera(previewView, lifecycleOwner, context,
                setSourceInfo = { sourceInfo = it },
                onBrandsDetected = { detectedBrands = it },
                yolov5TFLiteDetector)
    }
    CameraPreview(previewView)
//    BoxWithConstraints(
//        modifier = Modifier.fillMaxSize(),
//        contentAlignment = Alignment.Center
//    ) {
//        with(LocalDensity.current) {
//            Box(
//                modifier = Modifier
//                    .size(
//                        height = sourceInfo.height.toDp(),
//                        width = sourceInfo.width.toDp()
//                    )
//                    .scale(
//                        calculateScale(
//                            constraints,
//                            sourceInfo,
//                            PreviewScaleType.CENTER_CROP
//                        )
//                    )
//            )
//            {
//                Log.e("CAMERA", "sourceInfo ${sourceInfo}")
//                Log.e("CAMERA", "previewView H ${previewView.height}, previewView W ${previewView.width}")
//
////                CameraPreview(previewView)
//                DetectedBrands(brands = detectedBrands)
//            }
//        }
//    }

    Log.e("CAMERA", "sourceInfo ${sourceInfo}")
     Log.e("CAMERA", "previewView H ${previewView.height}, previewView W ${previewView.width}")
    DetectedBrands(brands = detectedBrands)
}

@Composable
fun DetectedBrands(
    brands: List<Recognition>,
) {
    Log.e("CAMERA", "brands ${brands}")
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (brand in brands) {
            drawRect(
                Color.Gray, style = Stroke(2.dp.toPx()),
                topLeft = Offset(brand.getLocation().left, brand.getLocation().top*2.4f),
                size = Size(brand.getLocation().width(), brand.getLocation().height())
            )
//            drawCircle(Color.Gray,5f, Offset(brand.getLocation().left, brand.getLocation().top))
        }
    }
}

@Composable
private fun CameraPreview(previewView: PreviewView) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            previewView.apply {
                this.scaleType = PreviewView.ScaleType.FILL_START
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Preview is incorrectly scaled in Compose on some devices without this
//                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            previewView
        })
}

private fun ListenableFuture<ProcessCameraProvider>.configureCamera(
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    context: Context,
    setSourceInfo: (SourceInfo) -> Unit,
    onBrandsDetected: (List<Recognition>) -> Unit,
    yolov5TFLiteDetector: Yolov5TFLiteDetector
): ListenableFuture<ProcessCameraProvider> {
    addListener({

        val cameraSelector = CameraSelector.Builder().build()
        val preview = androidx.camera.core.Preview.Builder()
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val analysis = bindAnalysisUseCase(setSourceInfo, onBrandsDetected,yolov5TFLiteDetector, previewView)
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
    setSourceInfo: (SourceInfo) -> Unit,
    onBrandsDetected: (List<Recognition>) -> Unit,
    yolov5TFLiteDetector :Yolov5TFLiteDetector,
    previewView: PreviewView
): ImageAnalysis? {
    val cameraExecutor = Executors.newSingleThreadExecutor()
    val imageProcessor = try {
        ImageProcess()
    } catch (e: Exception) {
        Log.e("CAMERA", "Can not create image processor", e)
        return null
    }
    val builder = ImageAnalysis.Builder()
    val analysisUseCase = builder.build()

    var sourceInfoUpdated = false

    analysisUseCase.setAnalyzer(
        cameraExecutor
    ) { imageProxy: ImageProxy ->
        if (!sourceInfoUpdated) {
            setSourceInfo(obtainSourceInfo(imageProxy))
            sourceInfoUpdated = true
        }
        try {
            imageProcessor.processImageProxy(imageProxy, onBrandsDetected, previewView, yolov5TFLiteDetector )
        } catch (e: Exception) {
            Log.e(
                "CAMERA", "Failed to process image. Error: " + e.localizedMessage
            )
        }
    }
    return analysisUseCase
}

private fun obtainSourceInfo(imageProxy: ImageProxy): SourceInfo {
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    return if (rotationDegrees == 0 || rotationDegrees == 180) {
        SourceInfo(
            height = imageProxy.height, width = imageProxy.width
        )
    } else {
        SourceInfo(
            height = imageProxy.width, width = imageProxy.height
        )
    }
}

private fun calculateScale(
    constraints: Constraints,
    sourceInfo: SourceInfo,
    scaleType: PreviewScaleType
): Float {
    val heightRatio = constraints.maxHeight.toFloat() / sourceInfo.height
    val widthRatio = constraints.maxWidth.toFloat() / sourceInfo.width
    return when (scaleType) {
        PreviewScaleType.FIT_CENTER -> kotlin.math.min(heightRatio, widthRatio)
        PreviewScaleType.CENTER_CROP -> kotlin.math.max(heightRatio, widthRatio)
    }
}

data class SourceInfo(
    val width: Int,
    val height: Int,
)

private enum class PreviewScaleType {
    FIT_CENTER,
    CENTER_CROP
}

@Composable
@Preview
fun ProfileScreenPreview() {
    CameraScreen()
}