package com.bfoxnet.pocketdev

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * Material 3 Bottom Sheet, Context Menu, Confirmation Dialog, Input Dialog,
 * Choice Picker, and App Typography (`Plus Jakarta Sans`) helper.
 */
object ModernUiHelper {

    const val PRIMARY_COLOR_HEX = "#00695C"
    const val PRIMARY_LIGHT_HEX = "#26A69A"
    val PRIMARY_COLOR: Int = Color.parseColor(PRIMARY_COLOR_HEX)
    val PRIMARY_LIGHT_COLOR: Int = Color.parseColor(PRIMARY_LIGHT_HEX)

    @Volatile
    private var cachedRegular: Typeface? = null

    @Volatile
    private var cachedMedium: Typeface? = null

    @Volatile
    private var cachedSemiBold: Typeface? = null

    @Volatile
    private var cachedBold: Typeface? = null

    fun appFontRegular(context: Context): Typeface {
        cachedRegular?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull()
            ?: Typeface.SANS_SERIF
        cachedRegular = tf
        return tf
    }

    fun appFontMedium(context: Context): Typeface {
        cachedMedium?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_medium) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        cachedMedium = tf
        return tf
    }

    fun appFontSemiBold(context: Context): Typeface {
        cachedSemiBold?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_semibold) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        cachedSemiBold = tf
        return tf
    }

    fun appFontBold(context: Context): Typeface {
        cachedBold?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_bold) }.getOrNull()
            ?: Typeface.DEFAULT_BOLD
        cachedBold = tf
        return tf
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

    fun resolvePalette(context: Context): Palette {
        val settings = Settings(context)
        val theme = EditorController.themeById(settings.editorTheme)
        val cardBg = Color.parseColor(theme.ui.chromeBg)
        val surfaceBg = Color.parseColor(theme.ui.bg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        val accent = PRIMARY_COLOR
        val border = if (theme.dark) Color.parseColor("#338899AA") else Color.parseColor("#33666666")
        return Palette(
            cardBg = cardBg,
            surfaceBg = surfaceBg,
            fgColor = fg,
            subtleColor = subtle,
            accentColor = accent,
            borderColor = border
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
        btnRow.addView(confirmBtn)

        rootCard.addView(btnRow)
        dialog.setContentView(rootCard)
        configureDialogWindow(dialog, context, maxWidthDp = 360)
        dialog.show()
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
