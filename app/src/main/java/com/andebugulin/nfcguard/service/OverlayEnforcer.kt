package com.andebugulin.nfcguard.service

import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.ui.MainActivity

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Block a foreground app by drawing a full-screen `BLOCKED` view via
 * `SYSTEM_ALERT_WINDOW`.
 *
 * Used when [ForegroundDetectorService] is NOT running (accessibility
 * permission off). Force-close is preferred when accessibility is on,
 * because the overlay's own window event races badly with
 * accessibility on Samsung and some MIUI builds.
 *
 * Thread safety: show/hide are mutex-guarded so two concurrent ticks
 * can't try to addView/removeView the same View. The animation runs on
 * the main dispatcher inside [NonCancellable] so a coroutine cancel
 * mid-flip doesn't leave the view in a half-attached state.
 *
 * The overlay's own touch listener calls [onTouch] so the service can
 * re-check the foreground app (the user may have already navigated
 * away, in which case the overlay should hide itself).
 */
class OverlayEnforcer(
    private val context: Context,
    private val onTouch: () -> Unit
) : Enforcer {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mutex = Mutex()
    private var view: View? = null
    private var isShowing = false
    private var animating = false

    override suspend fun block(packageName: String) {
        showSafe()
    }

    override suspend fun onAllowed(currentApp: String, isLauncher: Boolean) {
        // Always attempt to hide — overlay may be lingering from a tick
        // when accessibility was off and we used the overlay path.
        hideSafe()
    }

    override fun onDestroy() {
        // Force cleanup on destroy. Runs on the main thread already
        // (Service.onDestroy contract). Do NOT take the mutex here —
        // hideSafe() may be holding it while waiting for an animation
        // callback on this same main thread → deadlock.
        try {
            view?.let { v ->
                windowManager.removeView(v)
                view = null
                isShowing = false
                android.util.Log.d(TAG, "Overlay force-removed in onDestroy")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cleaning up overlay in onDestroy: ${e.message}")
            view = null
            isShowing = false
        }
    }

    /**
     * Force-remove without animation. The service calls this on a mode
     * transition that arrived with empty activeModeIds to short-circuit
     * the race where the prior monitoring loop is about to show an
     * overlay with stale data.
     */
    fun forceHideImmediate() {
        try {
            view?.let { v ->
                v.animate()?.cancel()
                windowManager.removeView(v)
                view = null
                isShowing = false
                animating = false
                android.util.Log.d(TAG, "Force-removed stale overlay")
                AppLogger.log("SERVICE", "Force-removed stale overlay (0 active modes)")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error force-removing overlay: ${e.message}")
            view = null
            isShowing = false
            animating = false
        }
    }

    private suspend fun showSafe() {
        mutex.withLock {
            if (isShowing || animating) {
                android.util.Log.w(TAG, "Overlay already showing or animating, skipping")
                return
            }
            animating = true
            withContext(Dispatchers.Main + NonCancellable) {
                try {
                    if (view != null) {
                        android.util.Log.w(TAG, "Overlay view exists, cleaning up first")
                        try { windowManager.removeView(view) } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error removing old overlay: ${e.message}")
                        }
                        view = null
                    }
                    isShowing = show()
                } finally {
                    animating = false
                }
            }
        }
    }

    private suspend fun hideSafe() {
        mutex.withLock {
            if (!isShowing || animating) return
            animating = true
            withContext(Dispatchers.Main + NonCancellable) {
                suspendCoroutine { cont ->
                    hide(onComplete = {
                        isShowing = false
                        animating = false
                        cont.resume(Unit)
                    })
                }
            }
        }
    }

    private fun show(): Boolean {
        android.util.Log.d(TAG, "SHOWING OVERLAY")
        return try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            view = createBlockerView()
            view?.apply { alpha = 0f; scaleX = 0.95f; scaleY = 0.95f }
            windowManager.addView(view, params)
            view?.animate()
                ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)
                ?.setDuration(400)
                ?.setInterpolator(DecelerateInterpolator(1.5f))
                ?.start()

            AppLogger.log("SERVICE", "OVERLAY SHOWN successfully")
            true
        } catch (e: Exception) {
            AppLogger.log("SERVICE", "OVERLAY FAILED: ${e.javaClass.simpleName} - ${e.message}")
            android.util.Log.e(TAG, "FAILED TO SHOW OVERLAY", e)
            view = null
            false
        }
    }

    private fun hide(onComplete: () -> Unit) {
        android.util.Log.d(TAG, "HIDING OVERLAY")
        val v = view
        if (v == null) {
            onComplete()
            return
        }
        fun forceRemove() {
            try { windowManager.removeView(v) } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to force remove overlay: ${e.message}")
            }
            view = null
            onComplete()
        }
        try {
            v.animate()
                ?.alpha(0f)?.scaleX(0.98f)?.scaleY(0.98f)
                ?.setDuration(250)
                ?.setInterpolator(AccelerateInterpolator(1.2f))
                ?.withEndAction {
                    try {
                        windowManager.removeView(v)
                        view = null
                        AppLogger.log("SERVICE", "OVERLAY HIDDEN successfully")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to remove overlay in withEndAction: ${e.message}")
                        view = null
                    }
                    onComplete()
                }
                ?.start()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start hide animation: ${e.message}")
            forceRemove()
        }
    }

    private fun createBlockerView(): View {
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            isFocusable = true

            @Suppress("DEPRECATION")
            systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

            setOnTouchListener { _, _ ->
                android.util.Log.d(TAG, "Touch on overlay — re-checking foreground")
                onTouch()
                true
            }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(48, 48, 48, 48)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setOnApplyWindowInsetsListener { v, insets ->
                        @Suppress("DEPRECATION")
                        val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            insets.displayCutout?.safeInsetTop ?: insets.systemWindowInsetTop
                        } else {
                            insets.systemWindowInsetTop
                        }
                        v.setPadding(48, 48 + topInset, 48, 48)
                        insets
                    }
                }

                addView(label("BLOCKED", size = 48f, white = true, letterSpacing = 0.2f))
                addView(arrow(32f, marginV = 16))
                addView(label("TO UNLOCK:", size = 14f, white = false))
                addView(arrow(24f, marginV = 12))
                addView(openGuardianRow())
                addView(arrow(24f, marginV = 12))
                addView(label("TAP NFC TO UNLOCK", size = 14f, white = false))
            }
            addView(content)
        }
    }

    private fun label(text: String, size: Float, white: Boolean, letterSpacing: Float = 0.15f) =
        TextView(context).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(if (white) 0xFFFFFFFF.toInt() else 0xFF808080.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.letterSpacing = letterSpacing
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun arrow(size: Float, marginV: Int) = TextView(context).apply {
        text = "↓"
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(0xFF808080.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginV
            bottomMargin = marginV
        }
    }

    private fun openGuardianRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        addView(TextView(context).apply {
            text = "OPEN "
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        addView(Button(context).apply {
            text = "GUARDIAN"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
            isAllCaps = true
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                android.util.Log.d(TAG, "GUARDIAN button clicked")
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            }
        })
    }

    private companion object {
        const val TAG = "OVERLAY_ENFORCER"
    }
}
