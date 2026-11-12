package me.weishu.kernelsu.homework

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.view.PreviewView

/**
 * 悬浮窗视图。
 *
 * 收起时是一个圆形按钮，点开是一块面板：实时相机预览 + 拍照 / 解题 / 朗读 / 统计按钮 + 答案区。
 * 全程用代码搭界面，不新增资源文件，避免和底座的资源撞名。
 */
class FloatingHomeworkView(
    context: Context,
    private val callback: Callback,
) : FrameLayout(context) {

    interface Callback {
        fun onCapture()
        fun onSolve()
        fun onClear()
        fun onRead()
        fun onCopy()
        fun onMarkCorrect()
        fun onMarkWrong()
        fun onClose()
        fun onExpand()
        fun onOpenApp()
        fun onGrantCamera()
        fun onMove(dx: Int, dy: Int)
    }

    val previewView = PreviewView(context)

    private val fabView = TextView(context).apply {
        text = "拍"
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(d(18), d(18), d(18), d(18))
        setBackgroundDrawable(roundBackground(0xFFE8453C.toInt(), 0f))
        elevation = d(10).toFloat()
        isClickable = true
        isFocusable = true
        setOnClickListener { callback.onExpand() }
    }

    private val titleView = textView("一起作业", 15f, true, Color.WHITE)
    private val statsView = textView("答对 0 / 0", 12f, false, 0xFFB9C4CF.toInt())

    private val headerView = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(titleView)
        addView(LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(d(8), 1, 1f)
        })
        addView(statsView)
        addView(textView("✕", 15f, true, 0xFF9AA6B2.toInt()).apply {
            setOnClickListener { callback.onClose() }
        })
    }

    private val previewHolder = FrameLayout(context).apply {
        addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(120)
        ))
        setBackgroundColor(Color.BLACK)
    }

    private val hintView = textView("点「拍照」开始，可连拍多页", 12f, false, 0xFF9AA6B2.toInt())

    /** 相机权限缺失时显示，点了跳到系统的权限页去授权。 */
    private val grantChip = chip("去授权相机") { callback.onGrantCamera() }
        .apply { visibility = View.GONE }

    /** 答案超过一题时显示，点了切到下一题。 */
    private val chunkChip = chip("") { stepChunk() }.apply { visibility = View.GONE }

    private val answerView = TextView(context).apply {
        textSize = 13f
        setTextColor(0xFFEAF0F5.toInt())
        setPadding(d(8), d(8), d(8), d(8))
        typeface = Typeface.MONOSPACE
    }

    private val answerScroll = ScrollView(context).apply {
        addView(answerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(14), d(12), d(14), d(12))
        setBackgroundDrawable(roundBackground(0xE620242B.toInt(), 18f))

        addView(headerView)
        addView(hintView)
        addView(spacer())
        addView(previewHolder)
        addView(row(
            chip("拍照") { callback.onCapture() },
            chip("清空") { callback.onClear() },
            grantChip,
        ))
        addView(row(
            chip("解题") { callback.onSolve() },
            chip("朗读") { callback.onRead() },
            chip("复制") { callback.onCopy() },
        ))
        addView(row(chip("答对了") { callback.onMarkCorrect() }, chip("答错了") { callback.onMarkWrong() }))
        addView(chip("打开一起作业") { callback.onOpenApp() })
        addView(chunkChip)
        addView(
            answerScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).also {
                it.weight = 1f
                it.topMargin = d(6)
            }
        )
    }

    private val container = FrameLayout(context)

    /** 拖动起点，只在 [onInterceptTouchEvent] 里记。 */
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false

    private var expanded = false
    private var lastAnswer: String? = null

    /** 切好题的单题答案，[chunkIndex] 是当前显示的那题。 */
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0

    init {
        addView(container)
        container.addView(fabView, FrameLayout.LayoutParams(d(64), d(64)))
    }

    /**
     * 拖动用「拦截」而不是「自己吃掉触摸」。
     *
     * 早先给整个 View 挂 `setOnTouchListener` 且恒返回 true，子 View 就永远收不到触摸事件，
     * 悬浮窗里所有按钮都点不动 —— 这是最致命的体验问题。改成拦截模型：没有超过阈值的拖动
     * 就放行给子 View，按钮恢复正常。
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!dragRegion(event)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX.toInt()
                dragStartY = event.rawY.toInt()
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.toInt() - dragStartX
                val dy = event.rawY.toInt() - dragStartY
                if (abs(dx) > 4 || abs(dy) > 4) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> callback.onMove(
                event.rawX.toInt() - dragStartX,
                event.rawY.toInt() - dragStartY,
            )

            MotionEvent.ACTION_UP -> if (!dragging) callback.onExpand()
        }
        return true
    }

    /** 收起时整块都能拖；展开时只有顶部标题条能拖，其余区域留给按钮。 */
    private fun dragRegion(event: MotionEvent): Boolean =
        !expanded || event.y <= d(44)

    /** 展开 / 收起。 */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        container.removeAllViews()
        if (value) {
            val (w, h) = panelSize()
            container.addView(panelView, FrameLayout.LayoutParams(w, h))
        } else {
            container.addView(fabView, FrameLayout.LayoutParams(d(64), d(64)))
        }
    }

    /**
     * 面板尺寸。
     *
     * 面板原先写死 310×600dp，在小屏 / 横屏机器上会直接盖住整个屏幕、把 App 的界面挡得
     * 严严实实。改成按屏幕尺寸自适应，但留出上下左右的空档，让用户还能点到被盖住的东西。
     */
    private fun panelSize(): IntArray {
        val dm = android.util.DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        display.getMetrics(dm)
        val w = minOf(d(310), (dm.widthPixels - d(48)).coerceAtLeast(d(240)))
        val h = minOf(d(620), (dm.heightPixels - d(140)).coerceAtLeast(d(360)))
        return intArrayOf(w, h)
    }

    fun isExpanded(): Boolean = expanded

    fun setStats(total: Int, correct: Int, streak: Int) {
        statsView.text = "答对 $correct / $total" + if (streak > 1) "　连对 $streak" else ""
    }

    fun setHint(text: String) {
        hintView.text = text
    }

    fun setLoading(value: Boolean) {
        if (value) {
            lastAnswer = null
            chunks = emptyList()
            chunkIndex = 0
            answerView.text = "正在让 AI 看题…"
            renderChunk()
        } else if (chunks.isNotEmpty()) {
            answerView.text = chunks[chunkIndex]
        }
    }

    fun setAnswer(text: String) {
        lastAnswer = text
        // 一份作业可能有多题，按 AI 给出的 `#第N题` 标记切开，逐题显示、逐题朗读。
        chunks = AnswerSplitter.split(text)
        chunkIndex = 0
        answerView.text = chunks.firstOrNull().orEmpty()
        renderChunk()
    }

    fun setError(message: String) {
        lastAnswer = null
        chunks = emptyList()
        chunkIndex = 0
        answerView.text = "出错了：$message"
        renderChunk()
    }

    /** 相机权限有没有；没有就露出「去授权相机」。 */
    fun setGrantVisible(visible: Boolean) {
        grantChip.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun currentAnswer(): String = chunks.getOrNull(chunkIndex) ?: lastAnswer.orEmpty()

    private fun renderChunk() {
        val n = chunks.size
        chunkChip.visibility = if (n > 1) View.VISIBLE else View.GONE
        if (n > 1) chunkChip.text = "第 ${chunkIndex + 1}/$n 题　点我换题"
    }

    /** 切到下一题，循环。 */
    private fun stepChunk() {
        if (chunks.size <= 1) return
        chunkIndex = (chunkIndex + 1) % chunks.size
        answerView.text = chunks[chunkIndex]
        renderChunk()
        answerScroll.post { answerScroll.fullScroll(View.FOCUS_UP) }
    }

    private fun row(vararg children: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = d(6) }
        children.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.weight = 1f
                it.marginEnd = d(6)
            })
        }
    }

    private fun spacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(0x33FFFFFF)
    }

    private fun textView(text: String, sizeSp: Float, bold: Boolean, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(d(6), d(9), d(6), d(9))
            setBackgroundDrawable(roundBackground(0x33FFFFFF, 12f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun roundBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics
            )
        }

    private fun d(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun abs(v: Int): Int = if (v < 0) -v else v
}
