package com.ysar.stopfundwar.util

import android.content.Context
import org.tensorflow.lite.support.metadata.MetadataExtractor
import org.tensorflow.lite.support.common.FileUtil
import android.widget.Toast
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.common.ops.QuantizeOp
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.DequantizeOp
import android.graphics.RectF
import org.tensorflow.lite.nnapi.NnApiDelegate
import android.os.Build
import android.util.Log
import android.util.Size
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

class Yolov5TFLiteDetector {
    val inputSize = Size(640, 640)
    val outputSize = intArrayOf(1, 25200, 20)
    private var IS_INT8 = false
    private val DETECT_THRESHOLD = 0.25f
    private val IOU_THRESHOLD = 0.45f
    private val IOU_CLASS_DUPLICATED_THRESHOLD = 0.7f
    private val MODEL_YOLOV5S = "yolov5s-fp16-320-metadata.tflite"
    private val BEST = "best.tflite"

    //    private final String MODEL_YOLOV5S = "yolov5s-dynamic.tflite";
    private val MODEL_YOLOV5N = "yolov5n-fp16-320.tflite"
    private val MODEL_YOLOV5M = "yolov5m-fp16-320.tflite"
    private val MODEL_YOLOV5S_INT8 = "yolov5s-int8-320.tflite"
    val labelFile = "coco_label.txt"
    var input5SINT8QuantParams = MetadataExtractor.QuantizationParams(0.003921568859368563f, 0)
    var output5SINT8QuantParams = MetadataExtractor.QuantizationParams(0.006305381190031767f, 5)
    private var MODEL_FILE: String? = null
    private var tflite: Interpreter? = null
    private var associatedAxisLabels: List<String>? = null
    var options = Interpreter.Options()
//    var modelFile: String?
//        get() = MODEL_FILE
//        set(modelFile) {
//            when (modelFile) {
//                "best" -> {
//                    IS_INT8 = false
//                    MODEL_FILE = BEST
//                }
//                "yolov5s" -> {
//                    IS_INT8 = false
//                    MODEL_FILE = MODEL_YOLOV5S
//                }
//                "yolov5n" -> {
//                    IS_INT8 = false
//                    MODEL_FILE = MODEL_YOLOV5N
//                }
//                "yolov5m" -> {
//                    IS_INT8 = false
//                    MODEL_FILE = MODEL_YOLOV5M
//                }
//                "yolov5s-int8" -> {
//                    IS_INT8 = true
//                    MODEL_FILE = MODEL_YOLOV5S_INT8
//                }
//                else -> Log.i("tfliteSupport", "Only yolov5s/n/m/sint8 can be load!")
//            }
//        }

    /**
     * 初始化模型, 可以通过 addNNApiDelegate(), addGPUDelegate()提前加载相应代理
     *
     * @param activity
     */
    fun initialModel(activity: Context?) {
        // Initialise the model
        try {
            val tfliteModel: ByteBuffer = FileUtil.loadMappedFile(activity!!, "best.tflite")
            tflite = Interpreter(tfliteModel, options)
            Log.i("tfliteSupport", "Success reading model: $MODEL_FILE")
            associatedAxisLabels = FileUtil.loadLabels(activity, labelFile)
            Log.i("tfliteSupport", "Success reading label: " + labelFile)
        } catch (e: IOException) {
            Log.e("tfliteSupport", "Error reading model or label: ", e)
            Toast.makeText(activity, "load model error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 检测步骤
     *
     * @param bitmap
     * @return
     */
    fun detect(bitmap: Bitmap?): ArrayList<Recognition> {

        // yolov5s-tflite的输入是:[1, 320, 320,3], 摄像头每一帧图片需要resize,再归一化
        var yolov5sTfliteInput: TensorImage
        val imageProcessor: ImageProcessor
        if (IS_INT8) {
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize.height, inputSize.width, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .add(
                    QuantizeOp(
                        input5SINT8QuantParams.zeroPoint.toFloat(),
                        input5SINT8QuantParams.scale
                    )
                )
                .add(CastOp(DataType.UINT8))
                .build()
            yolov5sTfliteInput = TensorImage(DataType.UINT8)
        } else {
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize.height, inputSize.width, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()
            yolov5sTfliteInput = TensorImage(DataType.FLOAT32)
        }
        yolov5sTfliteInput.load(bitmap)
        yolov5sTfliteInput = imageProcessor.process(yolov5sTfliteInput)


        // yolov5s-tflite的输出是:[1, 6300, 85], 可以从v5的GitHub release处找到相关tflite模型, 输出是[0,1], 处理到320.
        var probabilityBuffer: TensorBuffer
        probabilityBuffer = if (IS_INT8) {
            TensorBuffer.createFixedSize(outputSize, DataType.UINT8)
        } else {
            TensorBuffer.createFixedSize(outputSize, DataType.FLOAT32)
        }

        // 推理计算
        if (null != tflite) {
            // 这里tflite默认会加一个batch=1的纬度
            tflite!!.run(yolov5sTfliteInput.buffer, probabilityBuffer.buffer)
        }

        // 这里输出反量化,需要是模型tflite.run之后执行.
        if (IS_INT8) {
            val tensorProcessor = TensorProcessor.Builder()
                .add(
                    DequantizeOp(
                        output5SINT8QuantParams.zeroPoint.toFloat(),
                        output5SINT8QuantParams.scale
                    )
                )
                .build()
            probabilityBuffer = tensorProcessor.process(probabilityBuffer)
        }

        // 输出数据被平铺了出来
        val recognitionArray = probabilityBuffer.floatArray
        // 这里将flatten的数组重新解析(xywh,obj,classes).
        val allRecognitions = ArrayList<Recognition>()
        for (i in 0 until outputSize[1]) {
            val gridStride = i * outputSize[2]
            // 由于yolov5作者在导出tflite的时候对输出除以了image size, 所以这里需要乘回去
            val x = recognitionArray[0 + gridStride] * inputSize.width
            val y = recognitionArray[1 + gridStride] * inputSize.height
            val w = recognitionArray[2 + gridStride] * inputSize.width
            val h = recognitionArray[3 + gridStride] * inputSize.height
            val xmin = Math.max(0.0, x - w / 2.0).toInt()
            val ymin = Math.max(0.0, y - h / 2.0).toInt()
            val xmax = Math.min(inputSize.width.toDouble(), x + w / 2.0).toInt()
            val ymax = Math.min(inputSize.height.toDouble(), y + h / 2.0).toInt()
            val confidence = recognitionArray[4 + gridStride]
            val classScores = Arrays.copyOfRange(recognitionArray, 5 + gridStride, 85 + gridStride)
            //            if(i % 1000 == 0){
//                Log.i("tfliteSupport","x,y,w,h,conf:"+x+","+y+","+w+","+h+","+confidence);
//            }
            var labelId = 0
            var maxLabelScores = 0f
            for (j in classScores.indices) {
                if (classScores[j] > maxLabelScores) {
                    maxLabelScores = classScores[j]
                    labelId = j
                }
            }
            val r = Recognition(
                labelId,
                "",
                maxLabelScores,
                confidence,
                RectF(xmin.toFloat(), ymin.toFloat(), xmax.toFloat(), ymax.toFloat())
            )
            allRecognitions.add(
                r
            )
        }
        //        Log.i("tfliteSupport", "recognize data size: "+allRecognitions.size());

        // 非极大抑制输出
        val nmsRecognitions = nms(allRecognitions)
        // 第二次非极大抑制, 过滤那些同个目标识别到2个以上目标边框为不同类别的
        val nmsFilterBoxDuplicationRecognitions = nmsAllClass(nmsRecognitions)

        // 更新label信息
        for (recognition in nmsFilterBoxDuplicationRecognitions) {
            val labelId = recognition.labelId
            val labelName = associatedAxisLabels!![labelId]
            recognition.labelName = labelName
        }
        return nmsFilterBoxDuplicationRecognitions
    }

    /**
     * 非极大抑制
     *
     * @param allRecognitions
     * @return
     */
    protected fun nms(allRecognitions: ArrayList<Recognition>): ArrayList<Recognition> {
        val nmsRecognitions = ArrayList<Recognition>()

        // 遍历每个类别, 在每个类别下做nms
        for (i in 0 until outputSize[2] - 5) {
            // 这里为每个类别做一个队列, 把labelScore高的排前面
            val pq = PriorityQueue<Recognition>(
                25200
            ) { l, r -> // Intentionally reversed to put high confidence at the head of the queue.
                java.lang.Float.compare(r.confidence!!, l.confidence!!)
            }

            // 相同类别的过滤出来, 且obj要大于设定的阈值
            for (j in allRecognitions.indices) {
//                if (allRecognitions.get(j).getLabelId() == i) {
                if (allRecognitions[j].labelId == i && allRecognitions[j].confidence!! > DETECT_THRESHOLD) {
                    pq.add(allRecognitions[j])
                    //                    Log.i("tfliteSupport", allRecognitions.get(j).toString());
                }
            }

            // nms循环遍历
            while (pq.size > 0) {
                // 概率最大的先拿出来
                val a = arrayOfNulls<Recognition>(pq.size)
                val detections: Array<Recognition> = pq.toArray(a)
                val max = detections[0]
                nmsRecognitions.add(max)
                pq.clear()
                for (k in 1 until detections.size) {
                    val detection = detections[k]
                    if (boxIou(max.getLocation(), detection.getLocation()) < IOU_THRESHOLD) {
                        pq.add(detection)
                    }
                }
            }
        }
        return nmsRecognitions
    }

    /**
     * 对所有数据不区分类别做非极大抑制
     *
     * @param allRecognitions
     * @return
     */
    protected fun nmsAllClass(allRecognitions: ArrayList<Recognition>): ArrayList<Recognition> {
        val nmsRecognitions = ArrayList<Recognition>()
        val pq = PriorityQueue<Recognition>(
            100
        ) { l, r -> // Intentionally reversed to put high confidence at the head of the queue.
            java.lang.Float.compare(r.confidence!!, l.confidence!!)
        }

        // 相同类别的过滤出来, 且obj要大于设定的阈值
        for (j in allRecognitions.indices) {
            if (allRecognitions[j].confidence!! > DETECT_THRESHOLD) {
                pq.add(allRecognitions[j])
            }
        }
        while (pq.size > 0) {
            // 概率最大的先拿出来
            val a = arrayOfNulls<Recognition>(pq.size)
            val detections: Array<Recognition> = pq.toArray(a)
            val max = detections[0]
            nmsRecognitions.add(max)
            pq.clear()
            for (k in 1 until detections.size) {
                val detection = detections[k]
                if (boxIou(
                        max.getLocation(),
                        detection.getLocation()
                    ) < IOU_CLASS_DUPLICATED_THRESHOLD
                ) {
                    pq.add(detection)
                }
            }
        }
        return nmsRecognitions
    }

    protected fun boxIou(a: RectF, b: RectF): Float {
        val intersection = boxIntersection(a, b)
        val union = boxUnion(a, b)
        return if (union <= 0) 1f else intersection / union
    }

    protected fun boxIntersection(a: RectF, b: RectF): Float {
        val maxLeft = if (a.left > b.left) a.left else b.left
        val maxTop = if (a.top > b.top) a.top else b.top
        val minRight = if (a.right < b.right) a.right else b.right
        val minBottom = if (a.bottom < b.bottom) a.bottom else b.bottom
        val w = minRight - maxLeft
        val h = minBottom - maxTop
        return if (w < 0 || h < 0) 0f else w * h
    }

    protected fun boxUnion(a: RectF, b: RectF): Float {
        val i = boxIntersection(a, b)
        return (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i
    }

    /**
     * 添加NNapi代理
     */
    fun addNNApiDelegate() {
        var nnApiDelegate: NnApiDelegate? = null
        // Initialize interpreter with NNAPI delegate for Android Pie or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//            NnApiDelegate.Options nnApiOptions = new NnApiDelegate.Options();
//            nnApiOptions.setAllowFp16(true);
//            nnApiOptions.setUseNnapiCpu(true);
            //ANEURALNETWORKS_PREFER_LOW_POWER：倾向于以最大限度减少电池消耗的方式执行。这种设置适合经常执行的编译。
            //ANEURALNETWORKS_PREFER_FAST_SINGLE_ANSWER：倾向于尽快返回单个答案，即使这会耗费更多电量。这是默认值。
            //ANEURALNETWORKS_PREFER_SUSTAINED_SPEED：倾向于最大限度地提高连续帧的吞吐量，例如，在处理来自相机的连续帧时。
//            nnApiOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
//            nnApiDelegate = new NnApiDelegate(nnApiOptions);
            nnApiDelegate = NnApiDelegate()
            options.addDelegate(nnApiDelegate)
            Log.i("tfliteSupport", "using nnapi delegate.")
        }
    }

    /**
     * 添加GPU代理
     */
    fun addGPUDelegate() {
        val compatibilityList = CompatibilityList()
        if (compatibilityList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatibilityList.bestOptionsForThisDevice
            val gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.i("tfliteSupport", "using gpu delegate.")
        } else {
            addThread(4)
        }
    }

    /**
     * 添加线程数
     * @param thread
     */
    fun addThread(thread: Int) {
        options.numThreads = thread
    }
}