package com.livescreensaver.tv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

class LoadingAnimationOverlay(private val context: Context) {
    private var textView: TextView? = null
    private var animationHandler: Handler? = null
    private var currentFrame = 0
    private var isAnimating = false
    
    enum class AnimationType {
        SPINNING_DOTS, PULSING, PROGRESS_BAR, WAVE
    }
    
    fun createLoadingView(animationType: AnimationType = AnimationType.SPINNING_DOTS, customText: String = "Loading"): TextView {
        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
        }
        
        animationHandler = Handler(Looper.getMainLooper())
        isAnimating = true
        
        when (animationType) {
            AnimationType.SPINNING_DOTS -> startSpinningDotsAnimation(customText)
            AnimationType.PULSING -> startPulsingAnimation(customText)
            AnimationType.PROGRESS_BAR -> startProgressBarAnimation(customText)
            AnimationType.WAVE -> startWaveAnimation(customText)
        }
        
        return textView!!
    }
    
    private fun startSpinningDotsAnimation(customText: String) {
        val spinningFrames = listOf(
            "⠋ $customText", "⠙ $customText", "⠹ $customText", "⠸ $customText",
            "⠼ $customText", "⠴ $customText", "⠦ $customText", "⠧ $customText",
            "⠇ $customText", "⠏ $customText"
        )
        
        val spinRunnable = object : Runnable {
            override fun run() {
                if (isAnimating && textView != null) {
                    textView!!.text = spinningFrames[currentFrame % spinningFrames.size]
                    currentFrame++
                    animationHandler?.postDelayed(this, 80)
                }
            }
        }
        animationHandler?.post(spinRunnable)
    }
    
    private fun startPulsingAnimation(customText: String) {
        val pulsingRunnable = object : Runnable {
            override fun run() {
                if (isAnimating && textView != null) {
                    val dots = when ((currentFrame / 5) % 4) {
                        0 -> "$customText ●"
                        1 -> "$customText ● ●"
                        2 -> "$customText ● ● ●"
                        else -> customText
                    }
                    textView!!.text = dots
                    val alpha = 0.3f + (0.7f * (currentFrame % 20) / 20f)
                    textView!!.alpha = alpha
                    currentFrame++
                    animationHandler?.postDelayed(this, 50)
                }
            }
        }
        animationHandler?.post(pulsingRunnable)
    }
    
    private fun startProgressBarAnimation(customText: String) {
        val progressRunnable = object : Runnable {
            override fun run() {
                if (isAnimating && textView != null) {
                    val barLength = 20
                    val progress = currentFrame % (barLength * 2)
                    val position = if (progress < barLength) progress else barLength * 2 - progress
                    
                    val bar = StringBuilder("[")
                    for (i in 0 until barLength) {
                        bar.append(if (i == position || i == position - 1) "█" else "░")
                    }
                    bar.append("] $customText")
                    textView!!.text = bar.toString()
                    currentFrame++
                    animationHandler?.postDelayed(this, 100)
                }
            }
        }
        animationHandler?.post(progressRunnable)
    }
    
    private fun startWaveAnimation(customText: String) {
        val waveFrames = listOf(
            "▁ $customText", "▃ $customText", "▄ $customText", "▅ $customText",
            "▆ $customText", "▇ $customText", "▆ $customText", "▅ $customText",
            "▄ $customText", "▃ $customText"
        )
        
        val waveRunnable = object : Runnable {
            override fun run() {
                if (isAnimating && textView != null) {
                    textView!!.text = waveFrames[currentFrame % waveFrames.size]
                    currentFrame++
                    animationHandler?.postDelayed(this, 100)
                }
            }
        }
        animationHandler?.post(waveRunnable)
    }
    
    fun stop() {
        isAnimating = false
        animationHandler?.removeCallbacksAndMessages(null)
        textView = null
    }
}