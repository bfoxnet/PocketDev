package com.bfoxnet.pocketdev

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Material 3 Bottom Sheet, Context Menu, Confirmation Dialog, Input Dialog,
 * Choice Picker, Multi-Font App Typography (`Plus Jakarta Sans`, `Google Sans`,
 * `Inter`, `Geist`, `Noto Sans`, `DM Sans`, `Rubik`, `Outfit`), Decoupled App UI Palette,
 * and 60–120fps Hardware-Accelerated Animation & Transition Engine.
 */
object ModernUiHelper {

    const val PRIMARY_COLOR_HEX = "#B85648"
    const val PRIMARY_LIGHT_HEX = "#D47264"
    val PRIMARY_COLOR: Int = Color.parseColor(PRIMARY_COLOR_HEX)
    val PRIMARY_LIGHT_COLOR: Int = Color.parseColor(PRIMARY_LIGHT_HEX)

    // Dedicated App UI Palette (decoupled from Editor Theme so Editor Theme applies ONLY to CodeEditor)
    const val UI_SURFACE_HEX = "#141318"
    const val UI_CHROME_HEX = "#1C1B21"
    const val UI_SYMBOL_HEX = "#25242C"
    const val UI_FG_HEX = "#ECE6E5"
    const val UI_SUBTLE_HEX = "#9F989E"
    const val UI_BORDER_HEX = "#38343E"

    val UI_SURFACE_COLOR: Int = Color.parseColor(UI_SURFACE_HEX)
    val UI_CHROME_COLOR: Int = Color.parseColor(UI_CHROME_HEX)
    val UI_SYMBOL_COLOR: Int = Color.parseColor(UI_SYMBOL_HEX)
    val UI_FG_COLOR: Int = Color.parseColor(UI_FG_HEX)
    val UI_SUBTLE_COLOR: Int = Color.parseColor(UI_SUBTLE_HEX)
    val UI_BORDER_COLOR: Int = Color.parseColor(UI_BORDER_HEX)

    /** Smooth spring-like cubic bezier for 60-120fps UI transitions. */
    val SPRING_INTERPOLATOR = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    @Volatile
    private var cachedFontId: String = ""

    @Volatile
    private var cachedRegular: Typeface? = null

    @Volatile
    private var cachedMedium: Typeface? = null

    @Volatile
    private var cachedSemiBold: Typeface? = null

    @Volatile
    private var cachedBold: Typeface? = null

    fun invalidateUiFontCache() {
        cachedFontId = ""
        cachedRegular = null
        cachedMedium = null
        cachedSemiBold = null
        cachedBold = null
    }

    private fun ensureFontCache(context: Context): Settings.Companion.UiFont {
        val selectedId = Settings(context).uiFontFamily
        val uiFont = Settings.uiFontById(selectedId)
        if (cachedFontId != uiFont.id) {
            cachedFontId = uiFont.id
            cachedRegular = null
            cachedMedium = null
            cachedSemiBold = null
            cachedBold = null
        }
        return uiFont
    }

    fun appFontRegular(context: Context): Typeface {
        val uiFont = ensureFontCache(context)
        cachedRegular?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, uiFont.regularRes) }.getOrNull()
            ?: Typeface.SANS_SERIF
        cachedRegular = tf
        return tf
    }

    fun appFontMedium(context: Context): Typeface {
        val uiFont = ensureFontCache(context)
        cachedMedium?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, uiFont.mediumRes) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        cachedMedium = tf
        return tf
    }

    fun appFontSemiBold(context: Context): Typeface {
        val uiFont = ensureFontCache(context)
        cachedSemiBold?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, uiFont.semiBoldRes) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        cachedSemiBold = tf
        return tf
    }

    fun appFontBold(context: Context): Typeface {
        val uiFont = ensureFontCache(context)
        cachedBold?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, uiFont.boldRes) }.getOrNull()
            ?: Typeface.DEFAULT_BOLD
        cachedBold = tf
        return tf
    }

    /**
     * Recursively applies the active UI font family (`Settings.uiFontFamily`) across [root],
     * skipping [CodeEditor] and any explicit monospace preview/code views (`tag == "monospace"`).
     */
    fun applyUiFontToViewTree(context: Context, root: View?, skipViewIds: Set<Int> = emptySet()) {
        if (root == null || root is CodeEditor) return
        if (root.id in skipViewIds || root.tag == "monospace") return
        if (root is TextView) {
            val currentTf = root.typeface
            if (currentTf != Typeface.MONOSPACE) {
                val isBold = currentTf?.isBold == true
                root.typeface = if (isBold) appFontBold(context) else appFontRegular(context)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyUiFontToViewTree(context, root.getChildAt(i), skipViewIds)
            }
        }
    }

    /**
     * Requests 60-120fps high refresh rate rendering on devices with 90Hz/120Hz displays.
     */
    fun enableHighRefreshRate(activity: Activity) {
        runCatching {
            val win = activity.window ?: return
            val params = win.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display
                } else {
                    activity.windowManager.defaultDisplay
                }
                val modes = display?.supportedModes
                if (!modes.isNullOrEmpty()) {
                    val currentMode = display.mode
                    val bestMode = modes
                        .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                        .maxByOrNull { it.refreshRate }
                        ?: modes.maxByOrNull { it.refreshRate }
                    if (bestMode != null) {
                        params.preferredDisplayModeId = bestMode.modeId
                    }
                }
            }
            params.preferredRefreshRate = 120f
            win.attributes = params
        }
    }

    /**
     * Attaches non-intrusive 60-120fps tactile press scale feedback (`1.0f -> 0.965f -> 1.0f`).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressAnimation(view: View, scaleDown: Float = 0.965f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(110L)
                        .setInterpolator(SPRING_INTERPOLATOR)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160L)
                        .setInterpolator(SPRING_INTERPOLATOR)
                        .start()
                }
            }
            false
        }
    }

    /**
     * Smoothly animates switching between multi-page views at 60-120fps.
     */
    fun animatePageSwitch(
        pages: List<View>,
        targetPage: View,
        forward: Boolean = true,
        durationMs: Long = 220L
    ) {
        val density = targetPage.resources.displayMetrics.density
        val offsetPx = (if (forward) 24f else -24f) * density
        for (page in pages) {
            if (page === targetPage) {
                if (page.visibility != View.VISIBLE) {
                    page.visibility = View.VISIBLE
                    page.alpha = 0f
                    page.translationX = offsetPx
                    page.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(durationMs)
                        .setInterpolator(SPRING_INTERPOLATOR)
                        .withLayer()
                        .start()
                } else {
                    page.alpha = 1f
                    page.translationX = 0f
                }
            } else if (page.visibility == View.VISIBLE) {
                page.animate().cancel()
                page.visibility = View.GONE
                page.alpha = 1f
                page.translationX = 0f
            }
        }
    }

    /**
     * Smoothly animates showing or hiding a collapsible bar or panel at 60-120fps.
     */
    fun animatePanelVisibility(
        view: View,
        show: Boolean,
        slideOffsetDp: Float = 12f,
        durationMs: Long = 200L,
        onEnd: (() -> Unit)? = null
    ) {
        val density = view.resources.displayMetrics.density
        val offsetPx = slideOffsetDp * density
        (view.parent as? ViewGroup)?.let { parent ->
            runCatching {
                val transition = AutoTransition().apply {
                    duration = durationMs
                    interpolator = FAST_OUT_SLOW_IN
                }
                TransitionManager.beginDelayedTransition(parent, transition)
            }
        }
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.translationY = offsetPx
                view.visibility = View.VISIBLE
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(durationMs)
                .setInterpolator(SPRING_INTERPOLATOR)
                .withEndAction { onEnd?.invoke() }
                .start()
        } else {
            if (view.visibility == View.GONE) {
                onEnd?.invoke()
                return
            }
            view.animate()
                .alpha(0f)
                .translationY(offsetPx * 0.6f)
                .setDuration((durationMs * 0.85f).toLong())
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                    onEnd?.invoke()
                }
                .start()
        }
    }

    /**
     * Smoothly animates a view's height change at 60-120fps.
     */
    fun animateViewHeight(view: View, targetHeightPx: Int, durationMs: Long = 220L) {
        val startHeight = view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: targetHeightPx
        if (startHeight == targetHeightPx) return
        ValueAnimator.ofInt(startHeight, targetHeightPx).apply {
            duration = durationMs
            interpolator = SPRING_INTERPOLATOR
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                view.layoutParams = view.layoutParams.apply { height = h }
                view.requestLayout()
            }
            start()
        }
    }

    /**
     * Smoothly cross-fades between [showView] and [hideView] at 60-120fps.
     */
    fun animateCrossFade(showView: View, hideView: View, durationMs: Long = 180L) {
        hideView.animate().cancel()
        showView.animate().cancel()
        if (hideView.visibility == View.VISIBLE) {
            hideView.animate()
                .alpha(0f)
                .setDuration((durationMs * 0.75f).toLong())
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withEndAction {
                    hideView.visibility = View.GONE
                    hideView.alpha = 1f
                }
                .start()
        } else {
            hideView.visibility = View.GONE
        }
        if (showView.visibility != View.VISIBLE) {
            showView.alpha = 0f
            showView.visibility = View.VISIBLE
        }
        showView.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(SPRING_INTERPOLATOR)
            .start()
    }

    /**
     * Smoothly animates a view entrance (for bottom sheets and dialogs).
     */
    private fun animateDialogEntrance(view: View, isBottomSheet: Boolean) {
        val density = view.resources.displayMetrics.density
        view.alpha = 0f
        if (isBottomSheet) {
            view.translationY = 36f * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(SPRING_INTERPOLATOR)
                .withLayer()
                .start()
        } else {
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(210L)
                .setInterpolator(SPRING_INTERPOLATOR)
                .withLayer()
                .start()
        }
    }

    data class MenuOption(
        val id: Int,
        val title: String,
        val subtitle: String? = null,
        @DrawableRes val iconRes: Int? = null,
        val badgeText: String? = null,
        val isDestructive: Boolean = false,
        val isSelected: Boolean = false,
        val action: () -> Unit
    )

    data class Palette(
        val cardBg: Int,
        val surfaceBg: Int,
        val fgColor: Int,
        val subtleColor: Int,
        val accentColor: Int,
        val borderColor: Int,
        val destructiveColor: Int = Color.parseColor("#FF6B6B")
    ) {
        val bgColor: Int get() = cardBg
        val cardColor: Int get() = cardBg
        val strokeColor: Int get() = borderColor
        val subColor: Int get() = subtleColor
        val inputBgColor: Int get() = surfaceBg
        val isDark: Boolean get() = isColorDark(cardBg)
    }

    fun isColorDark(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 < 0.55
    }

    /**
     * Resolves the global App UI Palette (using `#B85648` primary color and fixed UI surfaces
     * so Editor Theme changes only affect the CodeEditor, never the surrounding app UI).
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolvePalette(context: Context): Palette {
        return Palette(
            cardBg = UI_CHROME_COLOR,
            surfaceBg = UI_SURFACE_COLOR,
            fgColor = UI_FG_COLOR,
            subtleColor = UI_SUBTLE_COLOR,
            accentColor = PRIMARY_COLOR,
            borderColor = UI_BORDER_COLOR
        )
    }

    fun showModernCustomBottomSheet(
        context: Context,
        contentView: View,
        palette: Palette = resolvePalette(context),
        cancelable: Boolean = true
    ): Dialog {
        val density = context.resources.displayMetrics.density
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, (12 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }

        val dragPill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * density
                setColor(withAlpha(palette.subtleColor, 0x70))
            }
            layoutParams = LinearLayout.LayoutParams(
                (38 * density).toInt(),
                (4 * density).toInt()
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        wrapper.addView(dragPill)
        wrapper.addView(
            contentView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(wrapper)
        configureBottomSheetWindow(dialog, context, maxWidthDp = 460)
        dialog.show()
        animateDialogEntrance(wrapper, isBottomSheet = true)
        return dialog
    }

    fun showModernConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "Delete",
        cancelText: String = context.getString(android.R.string.cancel),
        @DrawableRes iconRes: Int? = R.drawable.ic_delete,
        isDestructive: Boolean = true,
        palette: Palette = resolvePalette(context),
        onConfirm: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (18 * density).toInt(),
                (20 * density).toInt(),
                (16 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val actionColor = if (isDestructive) palette.destructiveColor else palette.accentColor

        if (iconRes != null) {
            val iconBox = ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(actionColor)
                setPadding(
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * density
                    setColor(withAlpha(actionColor, 0x22))
                }
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (12 * density).toInt()
                }
            }
            headerRow.addView(iconBox)
        }

        val titleView = MaterialTextView(context).apply {
            text = title
            textSize = 15.5f
            setTextColor(palette.fgColor)
            typeface = appFontBold(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)
        rootCard.addView(headerRow)

        val messageView = MaterialTextView(context).apply {
            text = message
            textSize = 13f
            typeface = appFontRegular(context)
            setTextColor(palette.subtleColor)
            setLineSpacing(2.5f * density, 1.08f)
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
            setTextIsSelectable(true)
        }
        rootCard.addView(messageView)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val cancelBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = cancelText
            textSize = 12.5f
            isAllCaps = false
            typeface = appFontSemiBold(context)
            setTextColor(palette.subtleColor)
            minHeight = (40 * density).toInt()
            minimumHeight = (40 * density).toInt()
            setOnClickListener { dialog.dismiss() }
        }
        attachPressAnimation(cancelBtn)
        btnRow.addView(cancelBtn)

        val confirmBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = confirmText
            textSize = 12.5f
            isAllCaps = false
            typeface = appFontBold(context)
            minHeight = (40 * density).toInt()
            minimumHeight = (40 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(actionColor)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        attachPressAnimation(confirmBtn)
        btnRow.addView(confirmBtn)

        rootCard.addView(btnRow)
        dialog.setContentView(rootCard)
        configureDialogWindow(dialog, context, maxWidthDp = 360)
        dialog.show()
        animateDialogEntrance(rootCard, isBottomSheet = false)
    }

    fun showModernContextMenu(
        context: Context,
        title: String,
        subtitle: String? = null,
        @DrawableRes headerIconRes: Int? = null,
        palette: Palette = resolvePalette(context),
        options: List<MenuOption>
    ) {
        val density = context.resources.displayMetrics.density
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, (12 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }

        val dragPill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * density
                setColor(withAlpha(palette.subtleColor, 0x70))
            }
            layoutParams = LinearLayout.LayoutParams(
                (38 * density).toInt(),
                (4 * density).toInt()
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
        }
        rootCard.addView(dragPill)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (18 * density).toInt(),
                (2 * density).toInt(),
                (18 * density).toInt(),
                (10 * density).toInt()
            )
        }

        if (headerIconRes != null) {
            val iconBox = ImageView(context).apply {
                setImageResource(headerIconRes)
                imageTintList = ColorStateList.valueOf(palette.accentColor)
                setPadding(
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * density
                    setColor(withAlpha(palette.accentColor, 0x22))
                }
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (12 * density).toInt()
                }
            }
            headerRow.addView(iconBox)
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = MaterialTextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(palette.fgColor)
            typeface = appFontBold(context)
            setSingleLine(true)
        }
        titleCol.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = MaterialTextView(context).apply {
                text = subtitle
                textSize = 11.5f
                typeface = appFontRegular(context)
                setTextColor(palette.subtleColor)
                setSingleLine(true)
            }
            titleCol.addView(subView)
        }
        headerRow.addView(titleCol)
        rootCard.addView(headerRow)

        val divider = View(context).apply {
            setBackgroundColor(palette.borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt().coerceAtLeast(1)
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        rootCard.addView(divider)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (2 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
        }

        for (opt in options) {
            val itemColor = when {
                opt.isDestructive -> palette.destructiveColor
                opt.isSelected -> palette.accentColor
                else -> palette.fgColor
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (46 * density).toInt()
                setPadding(
                    (14 * density).toInt(),
                    (10 * density).toInt(),
                    (14 * density).toInt(),
                    (10 * density).toInt()
                )
                val baseBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(
                        if (opt.isSelected) withAlpha(palette.accentColor, 0x1F)
                        else Color.TRANSPARENT
                    )
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(withAlpha(palette.accentColor, 0x28)),
                    baseBg,
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * density
                        setColor(Color.WHITE)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2 * density).toInt()
                    bottomMargin = (2 * density).toInt()
                }
                setOnClickListener {
                    dialog.dismiss()
                    opt.action()
                }
            }
            attachPressAnimation(row, 0.98f)

            if (opt.iconRes != null) {
                val icon = ImageView(context).apply {
                    setImageResource(opt.iconRes)
                    imageTintList = ColorStateList.valueOf(itemColor)
                    layoutParams = LinearLayout.LayoutParams(
                        (20 * density).toInt(),
                        (20 * density).toInt()
                    ).apply {
                        marginEnd = (14 * density).toInt()
                    }
                }
                row.addView(icon)
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val label = MaterialTextView(context).apply {
                text = opt.title
                textSize = 13.5f
                setTextColor(itemColor)
                typeface = if (opt.isSelected || opt.isDestructive) appFontBold(context) else appFontMedium(context)
            }
            textCol.addView(label)

            if (!opt.subtitle.isNullOrBlank()) {
                val sub = MaterialTextView(context).apply {
                    text = opt.subtitle
                    textSize = 11.5f
                    typeface = appFontRegular(context)
                    setTextColor(palette.subtleColor)
                }
                textCol.addView(sub)
            }
            row.addView(textCol)

            if (!opt.badgeText.isNullOrBlank()) {
                val badge = MaterialTextView(context).apply {
                    text = opt.badgeText
                    textSize = 10f
                    setTextColor(palette.accentColor)
                    typeface = appFontBold(context)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * density
                        setStroke((1 * density).toInt(), withAlpha(palette.accentColor, 0x66))
                        setColor(withAlpha(palette.accentColor, 0x14))
                    }
                    setPadding(
                        (7 * density).toInt(),
                        (2 * density).toInt(),
                        (7 * density).toInt(),
                        (2 * density).toInt()
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * density).toInt() }
                }
                row.addView(badge)
            }

            if (opt.isSelected) {
                val checkBadge = MaterialTextView(context).apply {
                    text = "✓"
                    textSize = 14f
                    setTextColor(palette.accentColor)
                    typeface = appFontBold(context)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * density).toInt() }
                }
                row.addView(checkBadge)
            }

            listContainer.addView(row)
        }

        scroll.addView(listContainer)
        rootCard.addView(scroll)

        dialog.setContentView(rootCard)
        configureBottomSheetWindow(dialog, context, maxWidthDp = 420)
        dialog.show()
        animateDialogEntrance(rootCard, isBottomSheet = true)
    }

    fun showModernSingleChoiceDialog(
        context: Context,
        title: String,
        subtitle: String? = null,
        items: List<String>,
        checkedIndex: Int,
        palette: Palette = resolvePalette(context),
        neutralButtonText: String? = null,
        onNeutralClick: (() -> Unit)? = null,
        onSelected: (Int) -> Unit
    ) {
        val options = items.mapIndexed { idx, label ->
            MenuOption(
                id = idx,
                title = label,
                isSelected = (idx == checkedIndex),
                action = { onSelected(idx) }
            )
        }.toMutableList()

        if (!neutralButtonText.isNullOrBlank() && onNeutralClick != null) {
            options.add(
                MenuOption(
                    id = -1,
                    title = neutralButtonText,
                    iconRes = R.drawable.ic_settings,
                    action = onNeutralClick
                )
            )
        }

        showModernContextMenu(
            context = context,
            title = title,
            subtitle = subtitle,
            palette = palette,
            options = options
        )
    }

    fun showModernInputDialog(
        context: Context,
        title: String,
        subtitle: String? = null,
        initialValue: String = "",
        hint: String = "",
        confirmText: String = "Save",
        positiveText: String = confirmText,
        neutralText: String? = null,
        onNeutral: ((String) -> Unit)? = null,
        palette: Palette = resolvePalette(context),
        onConfirm: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (18 * density).toInt(),
                (20 * density).toInt(),
                (16 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }

        val titleView = MaterialTextView(context).apply {
            text = title
            textSize = 15.5f
            setTextColor(palette.fgColor)
            typeface = appFontBold(context)
        }
        rootCard.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = MaterialTextView(context).apply {
                text = subtitle
                textSize = 11.5f
                typeface = appFontRegular(context)
                setTextColor(palette.subtleColor)
                setPadding(0, (3 * density).toInt(), 0, 0)
            }
            rootCard.addView(subView)
        }

        val input = EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            textSize = 13.5f
            typeface = appFontMedium(context)
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subtleColor)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(
                (12 * density).toInt(),
                (11 * density).toInt(),
                (12 * density).toInt(),
                (11 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(palette.surfaceBg)
                setStroke((1.5f * density).toInt().coerceAtLeast(1), palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
            if (initialValue.isNotEmpty()) {
                val dotIdx = initialValue.lastIndexOf('.')
                if (dotIdx > 0) {
                    setSelection(0, dotIdx)
                } else {
                    setSelection(initialValue.length)
                }
            }
        }
        rootCard.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        if (!neutralText.isNullOrBlank() && onNeutral != null) {
            val neutralBtn = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = neutralText
                textSize = 12f
                isAllCaps = false
                typeface = appFontSemiBold(context)
                setTextColor(palette.accentColor)
                minHeight = (38 * density).toInt()
                minimumHeight = (38 * density).toInt()
                setOnClickListener {
                    val v = input.text?.toString()?.trim() ?: ""
                    dialog.dismiss()
                    onNeutral(v)
                }
            }
            attachPressAnimation(neutralBtn)
            btnRow.addView(neutralBtn)

            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            btnRow.addView(spacer)
        }

        val cancelBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = context.getString(android.R.string.cancel)
            textSize = 12f
            isAllCaps = false
            typeface = appFontSemiBold(context)
            setTextColor(palette.subtleColor)
            minHeight = (38 * density).toInt()
            minimumHeight = (38 * density).toInt()
            setOnClickListener { dialog.dismiss() }
        }
        attachPressAnimation(cancelBtn)
        btnRow.addView(cancelBtn)

        val confirmBtn = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = positiveText
            textSize = 12f
            isAllCaps = false
            typeface = appFontBold(context)
            minHeight = (38 * density).toInt()
            minimumHeight = (38 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(palette.accentColor)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                val v = input.text?.toString()?.trim() ?: ""
                dialog.dismiss()
                onConfirm(v)
            }
        }
        attachPressAnimation(confirmBtn)
        btnRow.addView(confirmBtn)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val v = input.text?.toString()?.trim() ?: ""
                dialog.dismiss()
                onConfirm(v)
                true
            } else {
                false
            }
        }

        rootCard.addView(btnRow)
        dialog.setContentView(rootCard)
        configureDialogWindow(dialog, context, maxWidthDp = 360, showKeyboard = true)
        dialog.show()
        animateDialogEntrance(rootCard, isBottomSheet = false)
        input.requestFocus()
    }

    private fun configureBottomSheetWindow(
        dialog: Dialog,
        context: Context,
        maxWidthDp: Int
    ) {
        val win = dialog.window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        val targetWidth = minOf((dm.widthPixels * 0.96f).toInt(), (maxWidthDp * dm.density).toInt())
        win.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        win.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        win.attributes = win.attributes?.apply {
            y = (10 * dm.density).toInt()
        }
    }

    private fun configureDialogWindow(
        dialog: Dialog,
        context: Context,
        maxWidthDp: Int,
        showKeyboard: Boolean = false
    ) {
        val win = dialog.window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        val targetWidth = minOf((dm.widthPixels * 0.90f).toInt(), (maxWidthDp * dm.density).toInt())
        win.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (showKeyboard) {
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
