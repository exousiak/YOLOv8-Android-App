package com.example.stt

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.view.animation.AnimationUtils
import android.widget.ImageView

class SplashScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_screen)

        Handler().postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 3000)
        val loadingAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate)
        val loadingImage = findViewById<ImageView>(R.id.loading_image)
        loadingImage.startAnimation(loadingAnimation)
    }
}