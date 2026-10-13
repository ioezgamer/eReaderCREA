package org.crea.reader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.cos

/**
 * Container of the reader that gives page turns a paper feel: it snapshots the current page,
 * lets the navigator jump to the next one underneath, and folds the snapshot away like a sheet of
 * paper (with a shadow cast on the page being revealed). Swipes trigger the same animation.
 */
class PageTurnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Whether the paper effect and swipe handling are active (false for scroll mode, TTS...). */
    var enabled: () -> Boolean = { false }

    /** Whether the book is read right to left, which swaps the swipe directions. */
    var rtl: () -> Boolean = { false }

    /** Moves the navigator one page without animating; returns false if there is no such page. */
    var move: (forward: Boolean) -> Boolean = { false }

    private var animating = false
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var velocity: VelocityTracker? = null

    fun turn(forward: Boolean) {
        val activity = context as? Activity
        if (animating) return
        if (!enabled() || width == 0 || height == 0 ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null
        ) {
            move(forward)
            return
        }
        animating = true
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        try {
            PixelCopy.request(activity.window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    startTurn(bitmap, forward)
                } else {
                    animating = false
                    move(forward)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            animating = false
            move(forward)
        }
    }

    private fun startTurn(bitmap: Bitmap, forward: Boolean) {
        val w = width.toFloat()
        val page = ImageView(context).apply {
            setImageBitmap(bitmap)
            layoutParams = LayoutParams(width, height)
            cameraDistance = 14000f * density
            pivotX = if (forward) 0f else w
            pivotY = height / 2f
        }
        val shadowWidth = (36 * density).toInt()
        val shadow = View(context).apply {
            layoutParams = LayoutParams(shadowWidth, height)
            background = GradientDrawable(
                if (forward) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        // The new page is revealed underneath; the snapshot hides the jump until it folds away.
        addView(shadow)
        addView(page)
        if (!move(forward)) {
            removeView(page)
            removeView(shadow)
            animating = false
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val angle = 90f * t
                page.rotationY = if (forward) -angle else angle
                page.colorFilter = PorterDuffColorFilter(
                    Color.argb((90 * t).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP
                )
                val edge = w * cos(Math.toRadians(angle.toDouble())).toFloat()
                shadow.translationX = if (forward) edge else w - edge - shadowWidth
                shadow.alpha = 1f - t * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeView(page)
                    removeView(shadow)
                    animating = false
                }
            })
            start()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!enabled()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (ev.pointerCount == 1 && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val dx = ev.x - downX
                swiping = false
                if (abs(dx) > 48 * density || abs(vx) > 700 * density) {
                    val left = dx < 0
                    turn(forward = if (rtl()) !left else left)
                }
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}
