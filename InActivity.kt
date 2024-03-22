package com.example.stt

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.stt.ml.BusIn
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.InspectableProperty
import java.time.temporal.ValueRange
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.*

class InActivity : AppCompatActivity() {

    lateinit var labels:List<String>
    var colors = listOf<Int>(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED)
    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap:Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model: BusIn
    lateinit var interpreter: Interpreter
    lateinit var tfliteModel: ByteBuffer
    lateinit var tts: TextToSpeech
    private val lastTTSRequestTime = mutableMapOf<String, Long>()
    private val ttsRequestInterval = 3000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_in)
        get_permission()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Toast.makeText(this, "TTS 초기화 성공", Toast.LENGTH_SHORT).show()
                tts.language = Locale.KOREA
            }
        }

        labels = FileUtil.loadLabels(this, "bus_in.txt")
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        model = BusIn.newInstance(this)

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageView = findViewById(R.id.imageView)
        tfliteModel = loadModelFile(this, "bus_in.tflite")  // Load the model file
        interpreter = Interpreter(tfliteModel)

        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }
            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                processCameraImage()
            }
        }
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun readLabelsFromFile(fileName: String): List<String> {
        val labels = mutableListOf<String>()
        try {
            val inputStream: InputStream = assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                labels.add(line!!)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return labels
    }

    private fun processCameraImage() {
        handler.post {
            try {
                val labels = readLabelsFromFile("bus_in.txt")

                val bitmap = textureView.bitmap ?: return@post
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, false)

                val floatBuffer = FloatBuffer.allocate(640 * 640 * 3)
                for (y in 0 until 640) {
                    for (x in 0 until 640) {
                        val pixel = resizedBitmap.getPixel(x, y)
                        floatBuffer.put(((pixel shr 16 and 0xFF) / 255f)) // R
                        floatBuffer.put(((pixel shr 8 and 0xFF) / 255f))  // G
                        floatBuffer.put(((pixel and 0xFF) / 255f))         // B
                    }
                }
                floatBuffer.rewind()

                val byteBuffer = ByteBuffer.allocateDirect(4 * 640 * 640 * 3)
                byteBuffer.order(ByteOrder.nativeOrder())
                byteBuffer.asFloatBuffer().put(floatBuffer)

                val outputArray = Array(1) { Array(9) { FloatArray(8400) } }
                interpreter.run(byteBuffer, outputArray)

                val detections = outputArray[0]

                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)
                val h = mutableBitmap.height
                val w = mutableBitmap.width

                val currentTime = System.currentTimeMillis()
                val ttsManager = TTSManager(this@InActivity)

                for (i in 0 until 8400) {
                    val detection = detections.map { it[i] }

                    // 클래스 확률 추출 및 가장 높은 확률의 클래스 찾기
                    val classScores = detection.slice(4..8)
                    val maxClassIndex = classScores.indices.maxByOrNull { classScores[it] } ?: -1
                    val maxScore = if (maxClassIndex != -1) classScores[maxClassIndex] else 0f
//                    val label = if (maxClassIndex != -1) "Class ${maxClassIndex + 1}" else "Unknown" // 클래스 라벨

                    if (maxScore > 0.6) { // 임계값 설정
                        val x = (detection[0] * w) - 70f
                        val y = (detection[1] * h) - 170f
                        val width = detection[2] * w
                        val height = detection[3] * h

                        paint.color = colors[maxClassIndex % colors.size] // 클래스별로 다른 색상
                        paint.style = Paint.Style.STROKE
//                        canvas.drawRect(x, y, x + width, y + height, paint)


                        // 클래스 라벨 및 점수 표시
                        paint.style = Paint.Style.FILL
                        paint.textSize = 60f // 글자 크기 설정
                        val di: Map<String, String> = mapOf(
                            "bell" to "하차벨",
                            "card" to "단말기",
                            "door" to "출입구",
                            "yellow" to "교통약자석",
                            "pink" to "교통약자석"
                        )
                        var busin = ""
                        val label = if (maxClassIndex >= 0 && maxClassIndex < labels.size) {
                            labels[maxClassIndex] // 실제 라벨 사용
                        } else {
                            "Unknown"
                        }

                        if (label == "bell" || label == "yellow" || label == "pink"){
                            busin = di[label] + "이 앞에 있습니다"
                        }
                        else{
                            busin = di[label] + "가 앞에 있습니다"
                        }


                        val lastRequestTime = lastTTSRequestTime[label] ?: 0
                        if (currentTime - lastRequestTime > ttsRequestInterval) {
                            ttsManager.enqueueTTS(busin)
                            lastTTSRequestTime[label] = currentTime
                        }

                    }
                }

                runOnUiThread {
                    imageView.setImageBitmap(mutableBitmap)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in processCameraImage: ${e.message}")
                e.printStackTrace()
            }
        }
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        // Close the Interpreter to release resources
//        if (::interpreter.isInitialized) {
//            interpreter.close()
//        }
//        if (::cameraDevice.isInitialized) {
//            cameraDevice.close() // Release camera resources
//        }
//        // ... other cleanup if necessary ...
//    }

    @SuppressLint("MissingPermission")
    fun open_camera(){
        cameraManager.openCamera(cameraManager.cameraIdList[0], object:CameraDevice.StateCallback(){
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(), null, null)
                    }
                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                    }
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {

            }

            override fun onError(p0: CameraDevice, p1: Int) {

            }
        }, handler)
    }

    fun get_permission(){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
            get_permission()
        }
    }
    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
class TTSManager(private val inActivity: InActivity) {
    private val ttsQueue = ArrayDeque<String>()
    private var isSpeaking = false

    init {
        inActivity.tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                GlobalScope.launch(Dispatchers.Main) {
                    isSpeaking = false
                    processQueue()
                }
            }

            override fun onError(utteranceId: String?) {
            }
        })
    }

    fun enqueueTTS(message: String) {
        ttsQueue.addLast(message)
        if (!isSpeaking) {
            processQueue()
        }
    }

    private fun processQueue() {
        if (ttsQueue.isNotEmpty()) {
            val message = ttsQueue.removeFirst()
            isSpeaking = true
            speakOut(message)
        }
    }

    private fun speakOut(message: String) {
        GlobalScope.launch(Dispatchers.Main) {
            inActivity.tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
            Toast.makeText(inActivity, message, Toast.LENGTH_SHORT).show()
            delay(7000)

            isSpeaking = false
            processQueue()
        }
    }
}
