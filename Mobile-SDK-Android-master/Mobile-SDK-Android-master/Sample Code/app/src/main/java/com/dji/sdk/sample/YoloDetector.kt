package com.dji.sdk.sample.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val classId: Int,
    val confidence: Float,
    val box: RectF // ORIGINAL bitmap coordinates
)

class YoloDetector(context: Context) {

    private val interpreter: Interpreter
    private val labels: List<String>

    // Discovered at runtime from model tensors
    private val inputW: Int
    private val inputH: Int
    private val numDet: Int
    private val numAttr: Int
    private val numClasses: Int

    // Tunables
    private val confThreshold = 0.5f
    private val iouThreshold = 0.45f

    init {
        // Make sure these filenames match your assets exactly
        val modelBuffer = FileUtil.loadMappedFile(context, "yolov5s_fp16.tflite")
        labels = FileUtil.loadLabels(context, "coco_labels.txt")

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseNNAPI(true)
        }
        interpreter = Interpreter(modelBuffer, options)

        // Input: [1, H, W, 3]
        val inShape = interpreter.getInputTensor(0).shape()
        inputH = inShape[1]
        inputW = inShape[2]

        // Output: [1, N, 5+nc]
        val outShape = interpreter.getOutputTensor(0).shape()
        numDet = outShape[1]
        numAttr = outShape[2]

        val modelNc = numAttr - 5
        numClasses = if (labels.size == modelNc) labels.size else modelNc
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        // Preprocess to model input size
        val resized = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
        val input = preprocess(resized)

        // Always float output buffer
        val outputFloat = Array(1) { Array(numDet) { FloatArray(numAttr) } }

        // Wrap in map with explicit Any cast
        val outputMap: MutableMap<Int, Any> = mutableMapOf(0 to outputFloat as Any)

        // Run inference
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)

        // Postprocess back to original bitmap size
        return postprocess(outputFloat[0], bitmap.width.toFloat(), bitmap.height.toFloat())
    }


    private fun preprocess(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * inputH * inputW * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buf.putFloat(r); buf.putFloat(g); buf.putFloat(b)
        }
        buf.rewind()
        return buf
    }

    private fun postprocess(
        output: Array<FloatArray>,
        origW: Float,
        origH: Float
    ): List<DetectionResult> {
        val results = ArrayList<DetectionResult>(32)

        val sx = origW / inputW
        val sy = origH / inputH

        for (i in 0 until numDet) {
            val row = output[i]
            if (row.size < 6) continue

            val cx = row[0]; val cy = row[1]
            val w  = row[2]; val h  = row[3]
            val obj = row[4]
            if (obj <= 0f) continue

            // best class
            var bestId = -1
            var bestScore = 0f
            for (c in 5 until row.size) {
                val s = row[c]
                if (s > bestScore) { bestScore = s; bestId = c - 5 }
            }
            if (bestId < 0) continue

            val conf = obj * bestScore
            if (conf < confThreshold) continue

            // xywh -> xyxy in input space, then scale to original
            val left   = max(0f, cx - w / 2f) * sx
            val top    = max(0f, cy - h / 2f) * sy
            val right  = min(inputW.toFloat(), cx + w / 2f) * sx
            val bottom = min(inputH.toFloat(), cy + h / 2f) * sy

            results.add(
                DetectionResult(
                    classId = bestId,
                    confidence = conf,
                    box = RectF(left, top, right, bottom)
                )
            )
        }

        return nms(results, iouThreshold)
    }

    private fun nms(dets: List<DetectionResult>, iouTh: Float): List<DetectionResult> {
        if (dets.isEmpty()) return emptyList()
        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<DetectionResult>(sorted.size)
        val removed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            keep.add(a)
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                if (iou(a.box, sorted[j].box) > iouTh) removed[j] = true
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val interW = max(0f, x2 - x1)
        val interH = max(0f, y2 - y1)
        val inter = interW * interH
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun getLabel(classId: Int): String = labels.getOrNull(classId) ?: classId.toString()
}
