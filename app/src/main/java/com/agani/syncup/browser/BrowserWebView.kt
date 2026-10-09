package com.agani.syncup.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/**
 * The browser's WebView. On top of a plain WebView it reports:
 * - scrolling ([onScroll]), so the address bar can slide away while the page scrolls down;
 * - a pull down at the very top of the page ([onPull] / [onPullRelease]) for pull to refresh.
 *
 * The pull starts only when WebView itself says the page can't scroll up any further (it
 * overscrolls at the top), so pages that scroll inside their own panels, or opt out of
 * overscroll (`overscroll-behavior`), keep their gestures — as in Chrome.
 */
@SuppressLint("ViewConstructor")
class BrowserWebView(context: Context) : WebView(context) {
    /** The page scrolled by [dy] px (positive = down) and is now at [y]. */
    var onScroll: ((dy: Int, y: Int) -> Unit)? = null

    /** How far the page is being pulled down (px); 0 = the pull ended. */
    var onPull: ((distance: Float) -> Unit)? = null

    /** The finger let go: [refresh] when it was pulled far enough. */
    var onPullRelease: ((refresh: Boolean) -> Unit)? = null

    private val threshold = 72 * resources.displayMetrics.density
    private var downY = 0f
    private var lastY = 0f
    private var startY = 0f
    private var touching = false
    private var multiTouch = false
    private var pulling = false

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(t - oldt, t)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // At the very top and the finger keeps going down: the pull begins here.
        if (clampedY && scrollY == 0 && touching && !multiTouch && !pulling && lastY > downY) {
            pulling = true
            startY = lastY
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                multiTouch = false
                pulling = false
                downY = ev.y
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true // pinch zoom, not a pull
                endPull(false)
            }
            MotionEvent.ACTION_MOVE -> {
                lastY = ev.y
                if (pulling) {
                    val d = ev.y - startY
                    if (d <= 0f) endPull(false) else onPull?.invoke(d)
                }
            }
            MotionEvent.ACTION_UP -> {
                endPull(ev.y - startY >= threshold)
                touching = false
            }
            MotionEvent.ACTION_CANCEL -> {
                endPull(false)
                touching = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun endPull(refresh: Boolean) {
        if (!pulling) return
        pulling = false
        onPull?.invoke(0f)
        onPullRelease?.invoke(refresh)
    }
}
