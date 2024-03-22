package com.example.stt


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private var spokenText: String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        val outdetect: Button = findViewById(R.id.outobject)
        val indetect: Button = findViewById(R.id.inobject)
        outdetect.setOnClickListener {
            requestCameraPermission()
            val intent = Intent(this, OutActivity::class.java)
            startActivity(intent)
        }
        indetect.setOnClickListener {
            requestCameraPermission()
            val intent = Intent(this, InActivity::class.java)
            startActivity(intent)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Toast.makeText(this, "TTS 초기화 성공", Toast.LENGTH_SHORT).show()
                tts.language = Locale.KOREA
            }
        }

        btnSpeak.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "말씀해주세요")
            }
            startVoiceRecognition.launch(intent)
        }
    }
    private val startVoiceRecognition = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            spokenText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { it[0] } ?: ""
            //textView.text = spokenText
            val selectnum = "$spokenText 번으로 지정하였습니다"
            speakOut(selectnum)
        }
    }
    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }
    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val vibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
            vibrator.vibrate(vibrationEffect)
        } else {

            vibrator.vibrate(longArrayOf(0, 500, 200, 500), -1)
        }
    }

//    override fun onDestroy() {
//        if (::tts.isInitialized) {
//            tts.stop()
//            tts.shutdown()
//        }
//        super.onDestroy()
//    }
    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            } else {

                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
    companion object {
        private const val CAMERA_PERMISSION_CODE = 1
    }
}