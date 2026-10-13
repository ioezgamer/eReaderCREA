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
import kotlin.math.max
import kotlin.math.min

/**
 * Container of the reader that gives page turns a paper feel. It snapshots the current page, lets
 * the navigator jump to the neighbouring page underneath, and folds the snapshot away like a sheet
 * of paper (with a shadow cast on the page being revealed). While swiping, the sheet follows the
 * finger; on release it either completes the turn or falls back.
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

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Touch tracking.
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var velocity: VelocityTracker? = null
    private var lastDx = 0f
    private var lastVx = 0f

    // The sheet being turned. `busy` covers the snapshot, the drag and the settling animation.
    private var busy = false
    private var settling = false
    private var ready = false
    private var releasedEarly = false
    private var turnForward = true
    private var toLeft = true
    private var progress = 0f
    private var page: ImageView? = null
    private var shadow: View? = null
    private val shadowWidth = (36 * density).toInt()

    /** Turns one page, animated (used for taps on the screen edges). */
    fun turn(forward: Boolean) {
        if (busy) return
        prepare(forward) { ok -> if (ok) settle(commit = true) }
    }

    /**
     * Snapshots the current page, shows it on top and moves the navigator underneath. [onReady]
     * receives false when the turn is impossible (end of the book, snapshot failure) and the
     * caller has nothing to animate.
     */
    private fun prepare(forward: Boolean, onReady: (Boolean) -> Unit) {
        val activity = context as? Activity
        if (!enabled() || width == 0 || height == 0 ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null
        ) {
            move(forward)
            onReady(false)
            return
        }
        busy = true
        ready = false
        releasedEarly = false
        turnForward = forward
        toLeft = forward != rtl()
        progress = 0f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        val fail = {
            busy = false
            move(forward)
            onReady(false)
        }
        try {
            PixelCopy.request(activity.window, rect, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    fail()
                } else {
                    showSheet(bitmap)
                    // The new page appears underneath; the sheet hides the jump.
                    if (move(forward)) {
                        ready = true
                        onReady(true)
                    } else {
                        removeSheet()
                        busy = false
                        onReady(false)
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            fail()
        }
    }

    private fun showSheet(bitmap: Bitmap) {
        val w = width.toFloat()
        val h = height
        val pw = width
        val toLeft = toLeft
        val sheet = ImageView(context).apply {
            setImageBitmap(bitmap)
            layoutParams = LayoutParams(pw, h)
            cameraDistance = 14000f * density
            pivotX = if (toLeft) 0f else w
            pivotY = h / 2f
        }
        val cast = View(context).apply {
            layoutParams = LayoutParams(shadowWidth, h)
            background = GradientDrawable(
                if (toLeft) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        addView(cast)
        addView(sheet)
        page = sheet
        shadow = cast
        applyFold(0f)
    }

    private fun removeSheet() {
        page?.let { removeView(it) }
        shadow?.let { removeView(it) }
        page = null
        shadow = null
    }

    /** Folds the sheet: 0 = flat on the page, 1 = edge-on, fully turned. */
    private fun applyFold(t: Float) {
        val sheet = page ?: return
        val cast = shadow ?: return
        progress = t
        val w = width.toFloat()
        val angle = 90f * t
        sheet.rotationY = if (toLeft) -angle else angle
        sheet.colorFilter = PorterDuffColorFilter(
            Color.argb((60 * t).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP
        )
        val edge = w * cos(Math.toRadians(angle.toDouble())).toFloat()
        cast.translationX = if (toLeft) edge else w - edge - shadowWidth
        cast.alpha = 1f - t * t
    }

    /** Finishes the turn (the sheet goes away) or cancels it (the sheet falls back). */
    private fun settle(commit: Boolean) {
        val from = progress
        val to = if (commit) 1f else 0f
        if (!commit) {
            // Put the navigator back under the sheet before it lays down again.
            move(!turnForward)
        }
        settling = true
        ValueAnimator.ofFloat(from, to).apply {
            duration = (120 + 300 * abs(to - from)).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { applyFold(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeSheet()
                    settling = false
                    busy = false
                }
            })
            start()
        }
    }

    private fun towardsTurn(dx: Float) = if (toLeft) -dx else dx

    private fun finishDrag(dx: Float, vx: Float) {
        val fling = towardsTurn(vx) > 700 * density
        val backFling = towardsTurn(vx) < -700 * density
        val commit = (progress > 0.3f || fling || towardsTurn(dx) > 56 * density) && !backFling
        settle(commit)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (settling) return true
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
                if (!busy && ev.pointerCount == 1 && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    val forward = if (rtl()) dx > 0 else dx < 0
                    prepare(forward) { ok ->
                        if (!ok) {
                            swiping = false
                        } else if (releasedEarly) {
                            finishDrag(lastDx, lastVx)
                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (settling) return true
        if (!swiping) return super.onTouchEvent(ev)
        velocity?.addMovement(ev)
        val dx = ev.x - downX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (ready) {
                    applyFold(min(1f, max(0f, towardsTurn(dx) / (width * 0.85f))))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                lastDx = if (cancelled) 0f else dx
                lastVx = if (cancelled) 0f else velocity?.xVelocity ?: 0f
                swiping = false
                if (busy) {
                    if (ready) finishDrag(lastDx, lastVx) else releasedEarly = true
                }
            }
        }
        return true
    }
}
