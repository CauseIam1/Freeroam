package com.freeroam.tactical

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible

/**
 * Polaris Dynamic Avatar Overlay
 * 
 * A minimalist, Eve-style AI face visualization that appears during audio playback.
 * Uses geometric shapes with bioluminescent theming to create an ethereal presence.
 * 
 * Visual States:
 * - ACTIVATED: Appears when sphere transitions from Busy to Idle (audio playback starts)
 * - BREATHING: Slow rhythmic pulse when audio is paused
 * - DISMISSED: Fades out when audio playback completes
 */
class PolarisAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Avatar states
        const val STATE_HIDDEN = 0
        const val STATE_ACTIVE = 1
        const val STATE_PAUSED = 2

        // Color constants matching the app's bioluminescent theme
        private val ELECTRIC_BLUE = Color.parseColor("#1E88E5")  // AI Bubble Electric Blue
        private val STATUS_BUSY_BLUE = Color.parseColor("#03A9F4")  // Status Sphere Busy Blue
        private val STATUS_IDLE_BLUE = Color.parseColor("#42A5F5")  // Status Sphere Idle Blue
        private val BIOLUMINESCENT_GLOW = Color.parseColor("#64B5F6")  // Soft glow
        private val CORE_WHITE = Color.parseColor("#E3F2FD")  // Bright core
    }

    // State
    private var currentState = STATE_HIDDEN
    private var alphaValue = 0f  // For fade in/out

    // Paint objects for efficient rendering
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                Color.argb(180, 30, 136, 229),  // Electric blue center
                Color.argb(80, 30, 136, 229),   // Mid glow
                Color.TRANSPARENT                // Fade to transparent
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ELECTRIC_BLUE
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            0f, 0f, 0.5f,
            intArrayOf(CORE_WHITE, BIOLUMINESCENT_GLOW, ELECTRIC_BLUE),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = BIOLUMINESCENT_GLOW
        alpha = 180
    }

    // Animation values
    private var faceScale = 1f
    private var breathPhase = 0f
    private var audioAmplitude = 0f
    private var mouthOpenness = 0f

    // Animators
    private var breathAnimator: ValueAnimator? = null
    private var fadeInAnimator: ValueAnimator? = null
    private var fadeOutAnimator: ValueAnimator? = null

    // Geometry paths
    private val facePath = Path()
    private val leftEyePath = Path()
    private val rightEyePath = Path()
    private val mouthPath = Path()

    init {
        // Set initial visibility
        isVisible = false
        
        // Start breath animation for paused state
        startBreathAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (currentState == STATE_HIDDEN && alphaValue <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        val scale = (Math.min(width, height) / 2f) * faceScale

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(scale, scale)

        // Apply alpha for fade in/out
        val effectiveAlpha = (alphaValue * 255).toInt().coerceIn(0, 255)

        // Draw outer glow halo
        glowPaint.alpha = effectiveAlpha * 60 / 255
        canvas.drawCircle(0f, 0f, 1.2f, glowPaint)

        // Draw face geometry with bioluminescent effect
        facePaint.alpha = effectiveAlpha
        corePaint.alpha = effectiveAlpha
        outlinePaint.alpha = effectiveAlpha * 180 / 255

        // Draw the minimalist Eve-style face
        drawEveFace(canvas, effectiveAlpha)

        canvas.restore()
    }

    private fun drawEveFace(canvas: Canvas, alpha: Int) {
        // Update geometry based on audio amplitude
        updateFaceGeometry()

        // Draw face outline (ethereal oval)
        facePaint.shader = LinearGradient(
            0f, -1f, 0f, 1f,
            intArrayOf(
                Color.argb(alpha, 100, 181, 246),  // Light blue top
                Color.argb(alpha, 30, 136, 229),   // Electric blue middle
                Color.argb(alpha, 2, 119, 189)     // Deeper blue bottom
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(facePath, facePaint)

        // Draw glowing eyes (almond shaped)
        drawEye(canvas, leftEyePath, alpha, isLeft = true)
        drawEye(canvas, rightEyePath, alpha, isLeft = false)

        // Draw mouth (reactive to audio amplitude)
        drawMouth(canvas, alpha)

        // Draw subtle facial contours
        drawFacialContours(canvas, alpha)
    }

    private fun drawEye(canvas: Canvas, eyePath: Path, alpha: Int, isLeft: Boolean) {
        // Eye glow
        val eyeDirection = if (isLeft) -1 else 1
        
        // Outer glow
        val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                0f, 0f, 0.3f,
                intArrayOf(
                    Color.argb(alpha * 80 / 255, 100, 181, 246),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(eyeDirection * 0.35f, -0.1f, 0.25f, eyeGlowPaint)

        // Eye core
        val eyeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alpha, 255, 255, 255)
        }
        canvas.drawPath(eyePath, eyeCorePaint)

        // Eye outline
        canvas.drawPath(eyePath, outlinePaint)
    }

    private fun drawMouth(canvas: Canvas, alpha: Int) {
        if (mouthOpenness < 0.01f) return

        val mouthGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                0f, 0f, mouthOpenness * 0.3f,
                intArrayOf(
                    Color.argb(alpha * 100 / 255, 3, 169, 244),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(mouthPath, mouthGlowPaint)

        val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alpha, 2, 119, 189)
        }
        canvas.drawPath(mouthPath, mouthFillPaint)
    }

    private fun drawFacialContours(canvas: Canvas, alpha: Int) {
        // Subtle cheek contours
        val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.02f
            color = Color.argb(alpha * 40 / 255, 100, 181, 246)
        }

        // Left cheek
        val leftCheek = Path().apply {
            moveTo(-0.6f, 0.2f)
            quadTo(-0.7f, 0.4f, -0.6f, 0.5f)
        }
        canvas.drawPath(leftCheek, contourPaint)

        // Right cheek
        val rightCheek = Path().apply {
            moveTo(0.6f, 0.2f)
            quadTo(0.7f, 0.4f, 0.6f, 0.5f)
        }
        canvas.drawPath(rightCheek, contourPaint)
    }

    private fun updateFaceGeometry() {
        // Amplitude modifier for reactive animation
        val amplitudeMod = 1f + audioAmplitude * 0.15f

        // Face outline (ethereal oval)
        facePath.reset()
        facePath.addOval(
            -0.7f * amplitudeMod,
            -0.9f * amplitudeMod,
            0.7f * amplitudeMod,
            0.85f * amplitudeMod,
            Path.Direction.CW
        )

        // Left eye (almond shape)
        leftEyePath.reset()
        leftEyePath.moveTo(-0.5f, -0.15f)
        leftEyePath.quadTo(-0.35f, -0.25f, -0.2f, -0.15f)
        leftEyePath.quadTo(-0.35f, -0.05f, -0.5f, -0.15f)

        // Right eye (almond shape)
        rightEyePath.reset()
        rightEyePath.moveTo(0.2f, -0.15f)
        rightEyePath.quadTo(0.35f, -0.25f, 0.5f, -0.15f)
        rightEyePath.quadTo(0.35f, -0.05f, 0.2f, -0.15f)

        // Mouth (curve based on amplitude - subtle smile that opens with audio)
        mouthPath.reset()
        val mouthCurve = 0.05f + mouthOpenness * 0.3f
        val mouthY = 0.5f + mouthOpenness * 0.2f
        mouthPath.moveTo(-0.3f, mouthY)
        mouthPath.quadTo(0f, mouthY + mouthCurve, 0.3f, mouthY)
        if (mouthOpenness > 0.01f) {
            mouthPath.quadTo(0f, mouthY + mouthCurve + mouthOpenness * 0.15f, -0.3f, mouthY)
        }
    }

    // ==================== State Management ====================

    /**
     * Show the avatar overlay - wrapper for activate()
     * Called when audio playback starts
     */
    fun showAvatar() {
        activate()
    }

    /**
     * Hide the avatar overlay - wrapper for dismiss()
     * Called when audio playback completes
     */
    fun hideAvatar() {
        dismiss()
    }

    /**
     * Hide the avatar overlay with a fade-out animation
     * @param durationMs Duration of the fade animation in milliseconds
     */
    fun hideAvatarWithFade(durationMs: Long = 300) {
        android.animation.ObjectAnimator.ofFloat(
            this,
            "alpha",
            1.0f,
            0.0f
        ).apply {
            duration = durationMs
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dismiss()
                    alpha = 1.0f // Reset alpha for next show
                }
            })
            start()
        }
    }

    /**
     * Set avatar to paused/breathing state
     * Called when user pauses audio playback
     */
    fun setPausedState() {
        setPaused(true)
    }

    fun activate() {
        if (currentState == STATE_ACTIVE) return

        currentState = STATE_ACTIVE
        isVisible = true

        // Cancel any existing animators
        fadeOutAnimator?.cancel()
        fadeOutAnimator = null

        // Fade in animation
        fadeInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                alphaValue = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isVisible = true
                }
            })
            start()
        }
    }

    fun setPaused(paused: Boolean) {
        if (paused) {
            currentState = STATE_PAUSED
            // Continue breath animation
            if (breathAnimator?.isRunning != true) {
                startBreathAnimation()
            }
        } else {
            currentState = STATE_ACTIVE
        }
    }

    fun dismiss() {
        if (currentState == STATE_HIDDEN) return

        currentState = STATE_HIDDEN

        // Cancel any existing animators
        fadeInAnimator?.cancel()
        fadeInAnimator = null

        // Fade out animation
        fadeOutAnimator = ValueAnimator.ofFloat(alphaValue, 0f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                alphaValue = animator.animatedValue as Float
                invalidate()
                if (alphaValue < 0.01f) {
                    isVisible = false
                }
            }
            start()
        }
    }

    // ==================== Audio Reactivity ====================

    fun updateAudioAmplitude(amplitude: Float) {
        audioAmplitude = amplitude.coerceIn(0f, 1f)
        
        // Map amplitude to mouth openness for visual feedback
        mouthOpenness = audioAmplitude * 0.4f
        
        if (currentState == STATE_ACTIVE) {
            invalidate()
        }
    }

    fun resetAudioAmplitude() {
        audioAmplitude = 0f
        mouthOpenness = 0f
    }

    // ==================== Animations ====================

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
            duration = 3000  // Slow 3-second breath cycle
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                breathPhase = animator.animatedValue as Float
                if (currentState == STATE_PAUSED) {
                    // Subtle breathing pulse
                    faceScale = 1f + (Math.sin(breathPhase.toDouble()) * 0.03).toFloat()
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathAnimator?.cancel()
        fadeInAnimator?.cancel()
        fadeOutAnimator?.cancel()
    }
}