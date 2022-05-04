package com.ysar.stopfundwar.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import com.ysar.stopfundwar.screen.SourceInfo
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.concurrent.Executor

class ImageProcess {
    private val kMaxChannelValue = 262143

    /**
     * cameraX planes数据处理成yuv字节数组
     * @param planes
     * @param yuvBytes
     */
    fun fillBytes(planes: Array<ImageProxy.PlaneProxy>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    /**
     * YUV转RGB
     * @param y
     * @param u
     * @param v
     * @return
     */
    fun YUV2RGB(y: Int, u: Int, v: Int): Int {
        // Adjust and check YUV values
        var y = y
        var u = u
        var v = v
        y = if (y - 16 < 0) 0 else y - 16
        u -= 128
        v -= 128

        // This is the floating point equivalent. We do the conversion in integer
        // because some Android devices do not have floating point in hardware.
        // nR = (int)(1.164 * nY + 2.018 * nU);
        // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
        // nB = (int)(1.164 * nY + 1.596 * nV);
        val y1192 = 1192 * y
        var r = y1192 + 1634 * v
        var g = y1192 - 833 * v - 400 * u
        var b = y1192 + 2066 * u

        // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
        r = if (r > kMaxChannelValue) kMaxChannelValue else if (r < 0) 0 else r
        g = if (g > kMaxChannelValue) kMaxChannelValue else if (g < 0) 0 else g
        b = if (b > kMaxChannelValue) kMaxChannelValue else if (b < 0) 0 else b
        return -0x1000000 or (r shl 6 and 0xff0000) or (g shr 2 and 0xff00) or (b shr 10 and 0xff)
    }

    /**
     * YUV420T转ARGB8888
     * @param yData
     * @param uData
     * @param vData
     * @param width
     * @param height
     * @param yRowStride
     * @param uvRowStride
     * @param uvPixelStride
     * @param out
     */
    fun YUV420ToARGB8888(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        out: IntArray
    ) {
        var yp = 0
        for (j in 0 until height) {
            val pY = yRowStride * j
            val pUV = uvRowStride * (j shr 1)
            for (i in 0 until width) {
                val uv_offset = pUV + (i shr 1) * uvPixelStride
                out[yp++] = YUV2RGB(
                    0xff and yData[pY + i].toInt(), 0xff and uData[uv_offset]
                        .toInt(), 0xff and vData[uv_offset].toInt()
                )
            }
        }
    }

    /**
     * 计算图片旋转矩阵
     * @param srcWidth
     * @param srcHeight
     * @param dstWidth
     * @param dstHeight
     * @param applyRotation
     * @param maintainAspectRatio
     * @return
     */
    fun getTransformationMatrix(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        applyRotation: Int,
        maintainAspectRatio: Boolean
    ): Matrix {
        val matrix = Matrix()
        if (applyRotation != 0) {
            if (applyRotation % 90 != 0) {
                Log.e("Rotation", "Rotation != 90°, got: " + Integer.toString(applyRotation))
            }

            // Translate so center of image is at origin.
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f)

            // Rotate around origin.
            matrix.postRotate(applyRotation.toFloat())
        }

        // Account for the already applied rotation, if any, and then determine how
        // much scaling is needed for each axis.
        val transpose = (Math.abs(applyRotation) + 90) % 180 == 0
        val inWidth = if (transpose) srcHeight else srcWidth
        val inHeight = if (transpose) srcWidth else srcHeight

        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            val scaleFactorX = dstWidth / inWidth.toFloat()
            val scaleFactorY = dstHeight / inHeight.toFloat()
            if (maintainAspectRatio) {
                // Scale by minimum factor so that dst is filled completely while
                // maintaining the aspect ratio. Some image may fall off the edge.
                val scaleFactor = Math.max(scaleFactorX, scaleFactorY)
                matrix.postScale(scaleFactor, scaleFactor)
            } else {
                // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY)
            }
        }
        if (applyRotation != 0) {
            // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f)
        }
        return matrix
    }

    companion object {
        //
        fun resizeImage(bitmap: Bitmap?, size: Size): TensorImage {
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(size.height, size.width, ResizeOp.ResizeMethod.BILINEAR))
                .build()
            var tImage = TensorImage(DataType.UINT8)

            // Analysis code for every frame
            // Preprocess the image
            tImage.load(bitmap)
            tImage = imageProcessor.process(tImage)


            // Create a container for the result and specify that this is a quantized model.
            // Hence, the 'DataType' is defined as UINT8 (8-bit unsigned integer)
            val probabilityBuffer =
                TensorBuffer.createFixedSize(intArrayOf(1, 1001), DataType.FLOAT32)
            return tImage
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    fun processImageProxy(
        image: ImageProxy,
        onDetectionFinished: (List<Recognition>) -> Unit,
        previewView: PreviewView,
        yolov5TFLiteDetector: Yolov5TFLiteDetector
    ) {
        val rotation = 0
        val previewHeight = previewView.height
        val previewWidth = previewView.width
        Log.e(
            "ImageProxy",
            "rotation ${rotation} ,previewHeight ${previewView.height} ,previewWidth ${previewView.width}"
        )

        val yuvBytes = arrayOfNulls<ByteArray>(3)
        val planes = image.planes
        val imageHeight = image.height
        val imageWidth = image.width

        Log.e(
            "ImageProxy",
            "imageHeight ${image.height} ,imageWidth ${image.width} "
        )

        fillBytes(planes, yuvBytes)
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val rgbBytes = IntArray(imageHeight * imageWidth)
        YUV420ToARGB8888(
            yuvBytes[0]!!,
            yuvBytes[1]!!,
            yuvBytes[2]!!,
            imageWidth,
            imageHeight,
            yRowStride,
            uvRowStride,
            uvPixelStride,
            rgbBytes
        )

        // Исходное изображение
        val imageBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        imageBitmap.setPixels(rgbBytes, 0, imageWidth, 0, 0, imageWidth, imageHeight)

        Log.e(
            "ImageProxy",
            "imageBitmap H ${imageBitmap.height} ,imageBitmap W ${imageBitmap.width} "
        )

        // Изображение адаптировано к экрану fill_start формат bitmap
        val scale = Math.max(
            previewHeight / (if (rotation % 180 == 0) imageWidth else imageHeight).toDouble(),
            previewWidth / (if (rotation % 180 == 0) imageHeight else imageWidth).toDouble()
        )

        Log.e(
            "ImageProxy",
            "scale  ${scale} "
        )
        val fullScreenTransform = getTransformationMatrix(
            imageWidth, imageHeight,
            (scale * imageHeight).toInt(), (scale * imageWidth).toInt(),
            if (rotation % 180 == 0) 90 else 0, false
        )

        // Полноразмерное растровое изображение для предварительного просмотра
        val fullImageBitmap = Bitmap.createBitmap(
            imageBitmap,
            0,
            0,
            imageWidth,
            imageHeight,
            fullScreenTransform,
            false
        )
        Log.e(
            "ImageProxyv",
            "fullImageBitmap H ${fullImageBitmap.height} ,fullImageBitmap W ${fullImageBitmap.width} "
        )
        // Обрезаем растровое изображение до того же размера, что и предварительный просмотр на экране
        val cropImageBitmap = Bitmap.createBitmap(
            fullImageBitmap, 0, 0,
            previewWidth, previewHeight
        )

        Log.e(
            "ImageProxy",
            "cropImageBitmap H ${cropImageBitmap.height} ,cropImageBitmap W ${cropImageBitmap.width} "
        )

        // Растровое изображение входа модели
        val previewToModelTransform = getTransformationMatrix(
            cropImageBitmap.width, cropImageBitmap.height,
            yolov5TFLiteDetector.inputSize.width,
            yolov5TFLiteDetector.inputSize.height,
            0, false
        )
        val modelInputBitmap = Bitmap.createBitmap(
            cropImageBitmap, 0, 0,
            cropImageBitmap.width, cropImageBitmap.height,
            previewToModelTransform, false
        )
        val modelToPreviewTransform = Matrix()
        previewToModelTransform.invert(modelToPreviewTransform)
        Log.e(
            "ImageProxy",
            "modelInputBitmap H ${modelInputBitmap.height} ,cropImageBitmap W ${modelInputBitmap.width} "
        )
        val recognitions = yolov5TFLiteDetector.detect(modelInputBitmap)
        onDetectionFinished(recognitions)
        image.close()
    }

}