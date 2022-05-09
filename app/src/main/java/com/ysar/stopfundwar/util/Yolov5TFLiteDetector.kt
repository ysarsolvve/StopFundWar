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
    val outputSize = intArrayOf(1, 25200, 34)
    private val DETECT_THRESHOLD = 0.25f
    private val IOU_THRESHOLD = 0.45f
    private val IOU_CLASS_DUPLICATED_THRESHOLD = 0.7f
    val labelFile = "coco_label.txt"
    private var MODEL_FILE: String = "best.tflite"
    private var tflite: Interpreter? = null
    private var associatedAxisLabels: List<String>? = null
    private var options = Interpreter.Options()

    /**
     * Инициализируйте модель, вы можете заранее загрузить соответствующего агента с помощью addNNApiDelegate(), addGPUDelegate()
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
     * Этапы тестирования
     *
     * @param bitmap
     * @return
     */
    fun detect(bitmap: Bitmap?): ArrayList<Recognition> {

        Log.e(
            "Detector",
            "Start"
        )

        // входные данные yolov5s-tflite: [1, 640, 640,3], камере необходимо изменить размер и нормализовать каждый кадр
        Log.e("Detector","imageProcessor Start")
        val imageProcessor: ImageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize.height, inputSize.width, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()
        var yolov5sTfliteInput: TensorImage = TensorImage(DataType.FLOAT32)

        yolov5sTfliteInput.load(bitmap)
        yolov5sTfliteInput = imageProcessor.process(yolov5sTfliteInput)

        Log.e("Detector","imageProcessor End")

        // Выход yolov5s-tflite: [1,25200,34],
        var probabilityBuffer: TensorBuffer = TensorBuffer.createFixedSize(outputSize, DataType.FLOAT32)

        Log.e("Detector","Инференциальные вычисления Start ${tflite}")
        // Инференциальные вычисления
        if (null != tflite) {
            // Здесь tflite по умолчанию добавит широту batch=1
            tflite!!.run(yolov5sTfliteInput.buffer, probabilityBuffer.buffer)
        }
        Log.e("Detector","Инференциальные вычисления End")

        // Выходные данные выкладываются плиткой
        val recognitionArray = probabilityBuffer.floatArray
        // Здесь происходит повторный разбор уплощенного массива (xywh,obj,classes).
        val allRecognitions = ArrayList<Recognition>()
        for (i in 0 until outputSize[1]) {
            val gridStride = i * outputSize[2]
            // Поскольку при экспорте tflite автор yolov5 разделил вывод на размер изображения, здесь нужно умножить его обратно
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

        Log.e("Detector","val nmsRecognitions = nms(allRecognitions) Start")

        // Неэкстремально подавленный выход
        val nmsRecognitions = nms(allRecognitions)

        Log.e("Detector","val nmsFilterBoxDuplicationRecognitions = nmsAllClass(nmsRecognitions) Start")

        // Второе неэкстремальное подавление, фильтрация тех, у которых более 2 границ одной цели идентифицированы как разные классы
        val nmsFilterBoxDuplicationRecognitions = nmsAllClass(nmsRecognitions)

        Log.e("Detector","Обновить информацию о метке Start")

        // Обновить информацию о метке
        for (recognition in nmsFilterBoxDuplicationRecognitions) {
            val labelId = recognition.labelId
            val labelName = associatedAxisLabels!![labelId]
            recognition.labelName = labelName
        }
        Log.e("Detector","Обновить информацию о метке End")
        return nmsFilterBoxDuplicationRecognitions
    }

    /**
     * Неэкстремальное ингибирование
     *
     * @param allRecognitions
     * @return
     */
    protected fun nms(allRecognitions: ArrayList<Recognition>): ArrayList<Recognition> {
        val nmsRecognitions = ArrayList<Recognition>()

        // итерация по каждой категории, делая nms по каждой категории
        for (i in 0 until outputSize[2] - 5) {
            // Создайте очередь для каждой категории, помещая в нее сначала те, у которых высокий показатель labelScore
            val pq = PriorityQueue<Recognition>(
                25200
            ) { l, r -> // Intentionally reversed to put high confidence at the head of the queue.
                java.lang.Float.compare(r.confidence!!, l.confidence!!)
            }

            // отфильтровать одинаковые категории, при этом obj должен быть больше установленного порога
            for (j in allRecognitions.indices) {
//                if (allRecognitions.get(j).getLabelId() == i) {
                if (allRecognitions[j].labelId == i && allRecognitions[j].confidence!! > DETECT_THRESHOLD) {
                    pq.add(allRecognitions[j])
                    //                    Log.i("tfliteSupport", allRecognitions.get(j).toString());
                }
            }

            // обход цикла nms
            while (pq.size > 0) {
                // Сначала убираются наиболее вероятные
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
     * Неэкстремальное подавление всех данных независимо от категории
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

        // отфильтровать одинаковые категории, при этом obj должен быть больше установленного порога
        for (j in allRecognitions.indices) {
            if (allRecognitions[j].confidence!! > DETECT_THRESHOLD) {
                pq.add(allRecognitions[j])
            }
        }
        while (pq.size > 0) {
            // Сначала убираются наиболее вероятные.
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
     * Добавление прокси-сервера NNapi
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
     * Добавление прокси-серверов GPU
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
     * Количество добавленных threads
     * @param thread
     */
    fun addThread(thread: Int) {
        options.numThreads = thread
    }
}