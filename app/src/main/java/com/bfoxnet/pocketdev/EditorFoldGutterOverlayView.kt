package com.bfoxnet.pocketdev

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.roundToInt

/**
 * Interactive 60–120fps Gutter Fold/Unfold Icon Overlay for [CodeEditor].
 *
 * Draws VS Code / JetBrains style chevron icons (`▾` for expanded foldable blocks,
 * `▸` for folded blocks) directly in the editor gutter next to line numbers, and
 * intercepts taps on those icons to fold/unfold the corresponding block in-place.
 * All touches outside a fold/unfold icon pass directly through to [CodeEditor].
 */
class EditorFoldGutterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class FoldIconState {
        EXPANDED,
        FOLDED
    }

    private data class HitTarget(
        val line0: Int,
        val state: FoldIconState,
        val bounds: RectF
    )

    private var editor: CodeEditor? = null
    private var controller: EditorController? = null
    private var settings: Settings = Settings(context)
    var onFoldStateChanged: ((line0: Int, message: String) -> Unit)? = null

    private val lineFoldStates = HashMap<Int, FoldIconState>()
    private val visibleHitTargets = ArrayList<HitTarget>()
    private var cachedTextSignature: Int = 0
    private var pressedLine0: Int = -1

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val chevronPath = Path()

    fun attachToEditor(codeEditor: CodeEditor, editorController: EditorController) {
        this.editor = codeEditor
        this.controller = editorController
        this.settings = Settings(context)
        refreshFoldableLines(force = true)
    }

    fun refreshFoldableLines(force: Boolean = false) {
        val ed = editor ?: return
        val ctrl = controller ?: return
        if (!settings.codeFoldingEnabled) {
            if (lineFoldStates.isNotEmpty()) {
                lineFoldStates.clear()
                postInvalidateOnAnimation()
            }
            return
        }
        val textStr = runCatching { ed.text.toString() }.getOrDefault("")
        val sig = textStr.hashCode()
        if (!force && sig == cachedTextSignature) {
            postInvalidateOnAnimation()
            return
        }
        cachedTextSignature = sig
        lineFoldStates.clear()

        val lines = textStr.split('\n')
        val markerRegex = Regex("/\\* ⋯ folded #(F\\d+) \\(\\d+ lines\\) ⋯ \\*/")
        for (i in lines.indices) {
            if (markerRegex.containsMatchIn(lines[i])) {
                lineFoldStates[i] = FoldIconState.FOLDED
            }
        }

        val ranges = ctrl.detectFoldableRanges(textStr)
        for (r in ranges) {
            if (r.startLine0 !in lineFoldStates) {
                lineFoldStates[r.startLine0] = FoldIconState.EXPANDED
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        visibleHitTargets.clear()
        val ed = editor ?: return
        val ctrl = controller ?: return
        if (!settings.codeFoldingEnabled || lineFoldStates.isEmpty() || ed.visibility != VISIBLE) {
            return
        }

        val density = resources.displayMetrics.density
        val rowHeight = runCatching { ed.rowHeight.toFloat() }.getOrDefault(18f * density).coerceAtLeast(12f)
        val scrollY = runCatching { ed.offsetY.toFloat() }.getOrDefault(0f)
        val scrollX = runCatching { ed.offsetX.toFloat() }.getOrDefault(0f)

        // Hide gutter fold icons if user horizontally scrolled deeply into code when wordwrap is off
        if (!ed.isWordwrap && scrollX > rowHeight * 1.5f) {
            return
        }

        val textRegionOffset = runCatching { ed.measureTextRegionOffset() }.getOrDefault(48f * density)
        val iconCenterX = (textRegionOffset - 9.5f * density).coerceAtLeast(8f * density)
        val hitHalfSize = (11f * density).coerceAtLeast(rowHeight * 0.48f)
        val chevronRadius = (3.4f * density).coerceAtLeast(3f)

        val firstVis = runCatching { ed.firstVisibleLine }.getOrDefault(0).coerceAtLeast(0)
        val lastVis = runCatching { ed.lastVisibleLine }.getOrDefault(firstVis + 40)
        val subtleColor = ctrl.currentEditorLineNumberColor()
        val accentColor = ModernUiHelper.PRIMARY_COLOR

        chevronPaint.strokeWidth = (1.65f * density).coerceAtLeast(1.5f)

        for ((line0, state) in lineFoldStates) {
            if (line0 < firstVis - 2 || line0 > lastVis + 2) continue

            val rowTopY = resolveLineTopY(ed, line0, rowHeight, scrollY)
            val centerY = rowTopY + rowHeight * 0.5f
            if (centerY < -rowHeight || centerY > height + rowHeight) continue

            val hitRect = RectF(
                iconCenterX - hitHalfSize,
                centerY - hitHalfSize,
                iconCenterX + hitHalfSize,
                centerY + hitHalfSize
            )
            visibleHitTargets.add(HitTarget(line0, state, hitRect))

            val isPressed = (line0 == pressedLine0)
            if (state == FoldIconState.FOLDED || isPressed) {
                val pillRadius = 6f * density
                val pillRect = RectF(
                    iconCenterX - 6.5f * density,
                    centerY - 6.5f * density,
                    iconCenterX + 6.5f * density,
                    centerY + 6.5f * density
                )
                pillBgPaint.color = ModernUiHelper.withAlpha(accentColor, if (isPressed) 0x55 else 0x2E)
                canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillBgPaint)
            }

            chevronPath.reset()
            if (state == FoldIconState.EXPANDED) {
                // Downward chevron `▾`
                chevronPaint.color = if (isPressed) accentColor else ModernUiHelper.withAlpha(subtleColor, 0xDF)
                chevronPath.moveTo(iconCenterX - chevronRadius, centerY - chevronRadius * 0.45f)
                chevronPath.lineTo(iconCenterX, centerY + chevronRadius * 0.55f)
                chevronPath.lineTo(iconCenterX + chevronRadius, centerY - chevronRadius * 0.45f)
            } else {
                // Rightward chevron `▸` (folded block)
                chevronPaint.color = accentColor
                chevronPath.moveTo(iconCenterX - chevronRadius * 0.45f, centerY - chevronRadius)
                chevronPath.lineTo(iconCenterX + chevronRadius * 0.55f, centerY)
                chevronPath.lineTo(iconCenterX - chevronRadius * 0.45f, centerY + chevronRadius)
            }
            canvas.drawPath(chevronPath, chevronPaint)
        }
    }

    private fun resolveLineTopY(ed: CodeEditor, line0: Int, rowHeight: Float, scrollY: Float): Float {
        return runCatching {
            val layoutObj = ed.javaClass.getMethod("getLayout").invoke(ed)
            if (layoutObj != null) {
                val getCharIndex = ed.text.javaClass.getMethod("getCharIndex", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val charIdx = getCharIndex.invoke(ed.text, line0, 0) as Int
                val getRowIdx = layoutObj.javaClass.getMethod("getRowIndexForPosition", Int::class.javaPrimitiveType)
                val rowIdx = getRowIdx.invoke(layoutObj, charIdx) as Int
                rowIdx * rowHeight - scrollY
            } else {
                line0 * rowHeight - scrollY
            }
        }.getOrElse {
            line0 * rowHeight - scrollY
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!settings.codeFoldingEnabled || visibleHitTargets.isEmpty()) {
            return false
        }
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = visibleHitTargets.firstOrNull { it.bounds.contains(x, y) } ?: return false
                pressedLine0 = hit.line0
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedLine0 < 0) return false
                val hit = visibleHitTargets.firstOrNull { it.line0 == pressedLine0 }
                if (hit == null || !hit.bounds.contains(x, y)) {
                    pressedLine0 = -1
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val targetLine = pressedLine0
                pressedLine0 = -1
                postInvalidateOnAnimation()
                if (targetLine >= 0) {
                    val hit = visibleHitTargets.firstOrNull { it.line0 == targetLine && it.bounds.contains(x, y) }
                    if (hit != null) {
                        OnboardingAndHapticsHelper.performExtraKeyHaptic(context, this)
                        val msg = controller?.toggleFoldBlockAtLine(hit.line0) ?: ""
                        refreshFoldableLines(force = true)
                        if (msg.isNotEmpty()) {
                            onFoldStateChanged?.invoke(hit.line0, msg)
                        }
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressedLine0 >= 0) {
                    pressedLine0 = -1
                    postInvalidateOnAnimation()
                    return true
                }
                return false
            }
        }
        return false
    }
}
