package com.bfoxnet.pocketdev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Termux `TerminalView` (`https://github.com/termux/termux-app`) implementation.
 *
 * Features:
 * - Pure black `#000000` default Termux viewport (customizable via persisted Termux Style palettes).
 * - Full wrapped-line scrollback history with `OverScroller` inertia fling & visual scrollbar thumb.
 * - Interactive on-canvas Text Selection mode with draggable start/end handles (`Select & Copy`, `Copy`, `Paste`).
 * - Supports Termux cursor styles (`block = 0`, `underline = 1`, `bar = 2`) and configurable blink rate.
 * - Supports foreground/background 24-bit TrueColor spans, bold, and underline rendering.
 * - Pinch-to-zoom font scaling (`9sp` to `26sp`) and sticky `CTRL`/`ALT`/`FN` modifiers.
 */
class ReTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var session: TerminalSession? = null
        set(value) {
            field?.onUpdate = null
            field = value
            value?.onUpdate = {
                post {
                    if (!isSelectingText) {
                        scrollOffsetLines = 0
                    }
                    invalidate()
                }
            }
            scrollOffsetLines = 0
            clearSelection()
            invalidate()
        }

    /** Sticky modifier states controlled by the Termux ExtraKeysView bar. */
    var ctrlDown: Boolean = false
    var ctrlStickyLocked: Boolean = false
    var altDown: Boolean = false
    var fnDown: Boolean = false

    private fun consumeCtrlAfterKeyPress() {
        if (!ctrlStickyLocked) {
            ctrlDown = false
        }
        onModifierReset?.invoke()
    }
    var onModifierReset: (() -> Unit)? = null
    var onLongPressContextMenu: (() -> Unit)? = null
    var onRequestOpenDrawer: (() -> Unit)? = null
    var onSelectionModeChanged: ((Boolean, String) -> Unit)? = null
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Termux cursor style: 0 = block, 1 = underline, 2 = bar. */
    var cursorStyle: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            invalidate()
        }

    var cursorBlinkRateMs: Long = 500L

    private var textSizeSp: Float = 13.5f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        }.getOrNull() ?: Typeface.MONOSPACE
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val spanBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D0D0D0")
        alpha = 210
    }
    private val selectionHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#5580DEEA")
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80DEEA")
    }
    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6680DEEA")
    }
    private val scrollThumbRect = RectF()

    private val scroller = OverScroller(context)
    private var lastFlingY: Int = 0
    private var scrollOffsetLines: Int = 0
    private var scrollRemainderPx: Float = 0f
    private var lastTotalWrappedLines: Int = 1
    private var cursorVisible: Boolean = true

    // Interactive Text Selection state (wrapped-line coordinates)
    var isSelectingText: Boolean = false
        private set
    private var selStartLine: Int = 0
    private var selStartCol: Int = 0
    private var selEndLine: Int = 0
    private var selEndCol: Int = 0
    private var draggingHandle: Int = 0 // 0 = none/anchor, 1 = start handle, 2 = end handle
    private var startHandleX: Float = 0f
    private var startHandleY: Float = 0f
    private var endHandleX: Float = 0f
    private var endHandleY: Float = 0f

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (cursorBlinkRateMs > 0) {
                cursorVisible = !cursorVisible
                invalidate()
                postDelayed(this, cursorBlinkRateMs)
            } else {
                cursorVisible = true
            }
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isSelectingText) {
                    selectWordAtTouch(e.x, e.y)
                    return true
                }
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this@ReTerminalView, InputMethodManager.SHOW_IMPLICIT)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPressContextMenu?.invoke()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isSelectingText) return false
                if (e1 != null && e1.x < 40 * resources.displayMetrics.density &&
                    e2.x - e1.x > 68 * resources.displayMetrics.density &&
                    abs(velocityX) > abs(velocityY) * 1.4f
                ) {
                    onRequestOpenDrawer?.invoke()
                    return true
                }
                val lineHeight = getLineHeightPx().coerceAtLeast(1f)
                val maxScrollLines = getMaxScrollLines()
                if (maxScrollLines > 0 && abs(velocityY) > abs(velocityX)) {
                    val curScrollPx = (scrollOffsetLines * lineHeight).toInt()
                    val maxScrollPx = (maxScrollLines * lineHeight).toInt()
                    lastFlingY = curScrollPx
                    scroller.fling(
                        0,
                        curScrollPx,
                        0,
                        velocityY.toInt(),
                        0,
                        0,
                        0,
                        maxScrollPx
                    )
                    postInvalidateOnAnimation()
                    return true
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isSelectingText) return true
                val lineHeight = getLineHeightPx().coerceAtLeast(1f)
                scrollRemainderPx += distanceY
                val deltaLines = (scrollRemainderPx / lineHeight).toInt()
                if (deltaLines != 0) {
                    scrollRemainderPx -= deltaLines * lineHeight
                    val maxScroll = getMaxScrollLines()
                    scrollOffsetLines = (scrollOffsetLines - deltaLines).coerceIn(0, maxScroll)
                    invalidate()
                }
                return true
            }
        }
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSizeSp = (textSizeSp * detector.scaleFactor).coerceIn(9f, 26f)
                textPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
                onFontSizeChanged?.invoke(textSizeSp)
                invalidate()
                return true
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            val lineHeight = getLineHeightPx().coerceAtLeast(1f)
            val maxScroll = getMaxScrollLines()
            val targetLines = (scroller.currY / lineHeight).toInt().coerceIn(0, maxScroll)
            if (targetLines != scrollOffsetLines) {
                scrollOffsetLines = targetLines
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, cursorBlinkRateMs.coerceAtLeast(250L))
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    fun setTerminalFontSizeSp(sizeSp: Float) {
        textSizeSp = sizeSp.coerceIn(9f, 26f)
        textPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        invalidate()
    }

    fun setTerminalBackground(color: Int) {
        bgPaint.color = color
        invalidate()
    }

    fun setCursorColor(color: Int) {
        cursorPaint.color = color
        cursorPaint.alpha = 210
        invalidate()
    }

    fun setSelectionAccentColor(color: Int) {
        selectionHandlePaint.color = color
        selectionHighlightPaint.color = Color.argb(
            0x55,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        scrollBarPaint.color = Color.argb(
            0x77,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        invalidate()
    }

    fun setTerminalTypeface(tf: Typeface) {
        textPaint.typeface = tf
        invalidate()
    }

    private fun getMaxScrollLines(): Int {
        val s = session ?: return 0
        val totalWrapped = max(lastTotalWrappedLines, s.snapshotLines(getVisibleCols()).lines.size)
        return max(0, totalWrapped - getVisibleRows() + 2)
    }

    // ---------------------------------------------------------------- Interactive Text Selection

    fun hasActiveSelection(): Boolean = isSelectingText && getSelectedText().isNotEmpty()

    /** Enters interactive text selection mode on the terminal canvas. */
    fun startTextSelection() {
        val s = session ?: return
        val snapshot = s.snapshotLines(getVisibleCols())
        val totalLines = snapshot.lines.size
        if (totalLines == 0) return

        val visibleRows = getVisibleRows()
        val endIdx = (totalLines - scrollOffsetLines).coerceIn(0, totalLines)
        val startIdx = max(0, endIdx - visibleRows)

        // Pick the last non-blank visible line (or the last line) as the initial selection
        var targetLine = (endIdx - 1).coerceAtLeast(startIdx)
        for (idx in (endIdx - 1) downTo startIdx) {
            val rowText = snapshot.lines[idx].spans.joinToString("") { it.text }.trimEnd()
            if (rowText.isNotEmpty()) {
                targetLine = idx
                break
            }
        }
        val lineStr = snapshot.lines.getOrNull(targetLine)?.spans?.joinToString("") { it.text } ?: ""
        selStartLine = targetLine
        selStartCol = 0
        selEndLine = targetLine
        selEndCol = lineStr.length.coerceAtLeast(1)
        isSelectingText = true
        notifySelectionChanged()
        invalidate()
    }

    /** Selects the entire terminal scrollback + screen transcript. */
    fun selectAllText() {
        val s = session ?: return
        val snapshot = s.snapshotLines(getVisibleCols())
        val totalLines = snapshot.lines.size
        if (totalLines == 0) return
        val lastLineLen = snapshot.lines.last().spans.sumOf { it.text.length }.coerceAtLeast(1)
        selStartLine = 0
        selStartCol = 0
        selEndLine = totalLines - 1
        selEndCol = lastLineLen
        isSelectingText = true
        notifySelectionChanged()
        invalidate()
    }

    /** Clears interactive text selection mode. */
    fun clearSelection() {
        if (!isSelectingText) return
        isSelectingText = false
        draggingHandle = 0
        onSelectionModeChanged?.invoke(false, "")
        invalidate()
    }

    /** Copies the currently selected terminal range (or falls back to full copy) and exits selection mode. */
    fun copySelectedText() {
        if (isSelectingText) {
            val selected = getSelectedText()
            if (selected.isNotEmpty()) {
                copyToClipboard("Termux Selection", selected)
                Toast.makeText(context, "Copied selection (${selected.length} chars)", Toast.LENGTH_SHORT).show()
                clearSelection()
                return
            }
        }
        copyTerminalText()
    }

    /** Extracts the exact text between `(selStartLine, selStartCol)` and `(selEndLine, selEndCol)`. */
    fun getSelectedText(): String {
        if (!isSelectingText) return ""
        val s = session ?: return ""
        val snapshot = s.snapshotLines(getVisibleCols())
        val lines = snapshot.lines
        if (lines.isEmpty()) return ""

        val (sLine, sCol, eLine, eCol) = normalizedSelectionBounds(lines.size)
        val sb = StringBuilder()
        for (lineIdx in sLine..eLine) {
            val rowText = lines[lineIdx].spans.joinToString("") { it.text }
            val fromCol = if (lineIdx == sLine) sCol.coerceIn(0, rowText.length) else 0
            val toCol = if (lineIdx == eLine) eCol.coerceIn(fromCol, rowText.length) else rowText.length
            if (toCol > fromCol) {
                sb.append(rowText.substring(fromCol, toCol))
            }
            if (lineIdx < eLine) {
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun normalizedSelectionBounds(totalLines: Int): IntArray {
        val maxLine = (totalLines - 1).coerceAtLeast(0)
        val aLine = selStartLine.coerceIn(0, maxLine)
        val bLine = selEndLine.coerceIn(0, maxLine)
        return if (aLine < bLine || (aLine == bLine && selStartCol <= selEndCol)) {
            intArrayOf(aLine, max(0, selStartCol), bLine, max(0, selEndCol))
        } else {
            intArrayOf(bLine, max(0, selEndCol), aLine, max(0, selStartCol))
        }
    }

    private fun notifySelectionChanged() {
        onSelectionModeChanged?.invoke(isSelectingText, getSelectedText())
    }

    private fun touchToLineAndCol(x: Float, y: Float): Pair<Int, Int> {
        val s = session ?: return 0 to 0
        val snapshot = s.snapshotLines(getVisibleCols())
        val totalLines = snapshot.lines.size.coerceAtLeast(1)
        val visibleRows = getVisibleRows()
        val endIdx = (totalLines - scrollOffsetLines).coerceIn(0, totalLines)
        val startIdx = max(0, endIdx - visibleRows)

        val lineHeight = getLineHeightPx().coerceAtLeast(1f)
        val charW = getCharWidthPx().coerceAtLeast(1f)
        val relRow = ((y - paddingTop - 6f) / lineHeight).toInt().coerceIn(0, visibleRows - 1)
        val lineIdx = (startIdx + relRow).coerceIn(0, totalLines - 1)
        val colIdx = ((x - paddingLeft - 6f) / charW).toInt().coerceIn(0, getVisibleCols())
        return lineIdx to colIdx
    }

    private fun selectWordAtTouch(x: Float, y: Float) {
        val s = session ?: return
        val snapshot = s.snapshotLines(getVisibleCols())
        if (snapshot.lines.isEmpty()) return
        val (lineIdx, colIdx) = touchToLineAndCol(x, y)
        val rowText = snapshot.lines.getOrNull(lineIdx)?.spans?.joinToString("") { it.text } ?: ""
        if (rowText.isEmpty()) return
        val safeCol = colIdx.coerceIn(0, (rowText.length - 1).coerceAtLeast(0))
        var left = safeCol
        var right = safeCol
        while (left > 0 && !rowText[left - 1].isWhitespace()) left--
        while (right < rowText.length && !rowText[right].isWhitespace()) right++
        selStartLine = lineIdx
        selStartCol = left
        selEndLine = lineIdx
        selEndCol = right.coerceAtLeast(left + 1)
        notifySelectionChanged()
        invalidate()
    }

    /** Pastes text from the Android system clipboard into the active terminal session. */
    fun pasteFromClipboard() {
        val s = session ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
        if (text.isNotEmpty()) {
            clearSelection()
            s.pasteText(text)
            scrollOffsetLines = 0
            invalidate()
        }
    }

    /** Copies terminal text (active selection if any, or full transcript) to the Android system clipboard. */
    fun copyTerminalText() {
        if (isSelectingText) {
            val sel = getSelectedText()
            if (sel.isNotEmpty()) {
                copyToClipboard("Termux Selection", sel)
                Toast.makeText(context, "Copied selection to clipboard", Toast.LENGTH_SHORT).show()
                clearSelection()
                return
            }
        }
        val s = session ?: return
        val text = s.getCopyableText()
        if (text.isBlank()) {
            Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard("Termux", text)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun getLineHeightPx(): Float {
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent + fm.leading) * 1.16f
    }

    private fun getCharWidthPx(): Float = textPaint.measureText("M")

    private fun getVisibleRows(): Int {
        val padV = paddingTop + paddingBottom + 12
        val avail = (height - padV).coerceAtLeast(1)
        return max(1, (avail / getLineHeightPx()).toInt())
    }

    private fun getVisibleCols(): Int {
        val padH = paddingLeft + paddingRight + 12
        val avail = (width - padH).coerceAtLeast(1)
        return max(20, (avail / getCharWidthPx()).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSelectingText) {
            val hitRadius = 32f * resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val distStart = hypot(event.x - startHandleX, event.y - startHandleY)
                    val distEnd = hypot(event.x - endHandleX, event.y - endHandleY)
                    draggingHandle = when {
                        distStart <= hitRadius && distStart <= distEnd -> 1
                        distEnd <= hitRadius -> 2
                        else -> {
                            val (l, c) = touchToLineAndCol(event.x, event.y)
                            selStartLine = l
                            selStartCol = c
                            selEndLine = l
                            selEndCol = c + 1
                            2
                        }
                    }
                    notifySelectionChanged()
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val (l, c) = touchToLineAndCol(event.x, event.y)
                    if (draggingHandle == 1) {
                        selStartLine = l
                        selStartCol = c
                    } else {
                        selEndLine = l
                        selEndCol = c
                    }
                    // Auto-scroll when dragging near top or bottom edge during selection
                    val edgeZone = 28f * resources.displayMetrics.density
                    val maxScroll = getMaxScrollLines()
                    if (event.y < edgeZone && scrollOffsetLines < maxScroll) {
                        scrollOffsetLines = (scrollOffsetLines + 1).coerceAtMost(maxScroll)
                    } else if (event.y > height - edgeZone && scrollOffsetLines > 0) {
                        scrollOffsetLines = (scrollOffsetLines - 1).coerceAtLeast(0)
                    }
                    notifySelectionChanged()
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingHandle = 0
                    notifySelectionChanged()
                    invalidate()
                }
            }
            gestureDetector.onTouchEvent(event)
            return true
        }

        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    clearSelection()
                    val str = text.toString()
                    if (str.length > 1 && !ctrlDown && !altDown) {
                        session?.pasteText(str)
                        scrollOffsetLines = 0
                        invalidate()
                    } else {
                        handleCommittedText(str)
                    }
                }
                return true
            }

            override fun performContextMenuAction(id: Int): Boolean {
                when (id) {
                    android.R.id.paste, android.R.id.pasteAsPlainText -> {
                        pasteFromClipboard()
                        return true
                    }
                    android.R.id.copy, android.R.id.cut -> {
                        copySelectedText()
                        return true
                    }
                    android.R.id.selectAll -> {
                        selectAllText()
                        return true
                    }
                }
                return super.performContextMenuAction(id)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) {
                    session?.sendBackspace()
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return handleKeyDown(event.keyCode, event)
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleCommittedText(str: String) {
        val s = session ?: return
        for (ch in str) {
            if (ctrlDown) {
                consumeCtrlAfterKeyPress()
                val lower = ch.lowercaseChar()
                if (lower == 'v') {
                    pasteFromClipboard()
                } else {
                    s.sendControlChar(lower)
                }
            } else if (altDown) {
                altDown = false
                onModifierReset?.invoke()
                s.sendInput("\u001b$ch")
            } else if (ch == '\n' || ch == '\r') {
                s.sendEnter()
            } else {
                s.sendChar(ch)
            }
        }
        scrollOffsetLines = 0
        invalidate()
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_PASTE -> {
                pasteFromClipboard()
                return true
            }
            KeyEvent.KEYCODE_COPY -> {
                copySelectedText()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                clearSelection()
                s.sendEnter()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                clearSelection()
                s.sendBackspace()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                s.sendTab()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                s.navigateHistory(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                s.navigateHistory(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                s.moveCursorHorizontal(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                s.moveCursorRightOrAcceptSuggestion()
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                s.moveCursorToHome()
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                s.moveCursorToEndOrAcceptSuggestion()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                val maxScroll = getMaxScrollLines()
                scrollOffsetLines = (scrollOffsetLines + max(5, getVisibleRows() / 2)).coerceIn(0, maxScroll)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                val maxScroll = getMaxScrollLines()
                scrollOffsetLines = (scrollOffsetLines - max(5, getVisibleRows() / 2)).coerceIn(0, maxScroll)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_V -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    pasteFromClipboard()
                    return true
                }
            }
            KeyEvent.KEYCODE_C -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    if (event.isShiftPressed || isSelectingText) {
                        copySelectedText()
                    } else {
                        s.sendControlChar('c')
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_L -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('l')
                    return true
                }
            }
            KeyEvent.KEYCODE_D -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('d')
                    return true
                }
            }
            KeyEvent.KEYCODE_A -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('a')
                    return true
                }
            }
            KeyEvent.KEYCODE_E -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('e')
                    return true
                }
            }
            KeyEvent.KEYCODE_U -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('u')
                    return true
                }
            }
            KeyEvent.KEYCODE_K -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('k')
                    return true
                }
            }
            KeyEvent.KEYCODE_W -> {
                if (event.isCtrlPressed || ctrlDown) {
                    consumeCtrlAfterKeyPress()
                    s.sendControlChar('w')
                    return true
                }
            }
        }
        val uni = event.unicodeChar
        if (uni != 0) {
            clearSelection()
            handleCommittedText(uni.toChar().toString())
            return true
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val s = session ?: return
        val snapshot = s.snapshotLines(getVisibleCols())
        val totalLines = snapshot.lines.size
        lastTotalWrappedLines = totalLines
        val visibleRows = getVisibleRows()
        val maxScroll = max(0, totalLines - visibleRows + 2)
        if (scrollOffsetLines > maxScroll) {
            scrollOffsetLines = maxScroll
        }
        val endIdx = (totalLines - scrollOffsetLines).coerceIn(0, totalLines)
        val startIdx = max(0, endIdx - visibleRows)

        val fm = textPaint.fontMetrics
        val lineHeight = getLineHeightPx()
        val charW = getCharWidthPx()
        val startX = paddingLeft + 6f
        var y = paddingTop + 6f - fm.ascent

        val selBounds = if (isSelectingText && totalLines > 0) {
            normalizedSelectionBounds(totalLines)
        } else {
            null
        }
        val density = resources.displayMetrics.density
        val handleRadius = 7f * density

        for (i in startIdx until endIdx) {
            val row = snapshot.lines[i]
            val top = y + fm.ascent
            val bot = y + fm.descent

            // Draw selection highlight if this wrapped line is inside active selection
            if (selBounds != null && i in selBounds[0]..selBounds[2]) {
                val rowLen = row.spans.sumOf { it.text.length }
                val hlStartCol = if (i == selBounds[0]) selBounds[1].coerceIn(0, rowLen) else 0
                val hlEndCol = if (i == selBounds[2]) selBounds[3].coerceIn(hlStartCol, max(rowLen, hlStartCol + 1)) else max(rowLen, 1)
                val hlLeft = startX + hlStartCol * charW
                val hlRight = startX + hlEndCol * charW
                canvas.drawRect(hlLeft, top, hlRight, bot, selectionHighlightPaint)

                if (i == selBounds[0]) {
                    startHandleX = hlLeft
                    startHandleY = bot + handleRadius
                }
                if (i == selBounds[2]) {
                    endHandleX = hlRight
                    endHandleY = bot + handleRadius
                }
            }

            var x = startX
            for (span in row.spans) {
                val spanW = span.text.length * charW
                if (span.bgColor != Color.TRANSPARENT) {
                    spanBgPaint.color = span.bgColor
                    canvas.drawRect(x, top, x + spanW, bot, spanBgPaint)
                }
                textPaint.color = span.fgColor
                textPaint.isFakeBoldText = span.bold
                textPaint.isUnderlineText = span.underline
                canvas.drawText(span.text, x, y, textPaint)
                x += spanW
            }

            // Draw cursor on the active input row according to Termux cursor style (block, underline, bar)
            if (i == snapshot.cursorRow && scrollOffsetLines == 0 && cursorVisible && !isSelectingText) {
                val cx = startX + snapshot.cursorCol * charW
                when (cursorStyle) {
                    1 -> {
                        canvas.drawRect(cx, bot - 4f, cx + min(charW, 20f), bot, cursorPaint)
                    }
                    2 -> {
                        canvas.drawRect(cx, top, cx + 3.5f, bot, cursorPaint)
                    }
                    else -> {
                        canvas.drawRect(cx, top, cx + min(charW, 20f), bot, cursorPaint)
                    }
                }
            }
            y += lineHeight
        }

        // Draw draggable selection handles when in selection mode
        if (selBounds != null) {
            if (selBounds[0] in startIdx until endIdx) {
                canvas.drawCircle(startHandleX, startHandleY, handleRadius, selectionHandlePaint)
            }
            if (selBounds[2] in startIdx until endIdx) {
                canvas.drawCircle(endHandleX, endHandleY, handleRadius, selectionHandlePaint)
            }
        }

        // Draw scrollbar thumb on the right edge when scrollback history exceeds viewport
        if (totalLines > visibleRows && height > 0) {
            val trackH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(16f)
            val thumbH = max(24f * density, trackH * (visibleRows.toFloat() / totalLines.toFloat()))
            val maxOffset = max(1, totalLines - visibleRows)
            val currentTopLine = startIdx.coerceIn(0, maxOffset)
            val fraction = currentTopLine.toFloat() / maxOffset.toFloat()
            val thumbTop = paddingTop + fraction * (trackH - thumbH)
            val barW = 3.5f * density
            scrollThumbRect.set(
                width - barW - 2f * density,
                thumbTop,
                width - 2f * density,
                thumbTop + thumbH
            )
            canvas.drawRoundRect(scrollThumbRect, barW / 2f, barW / 2f, scrollBarPaint)
        }
    }
}
