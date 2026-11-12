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
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
        fun onMove(dx: Int, dy: Int)
    }

    val previewView = TextureView(context)

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
        addView(spacer())
        addView(previewHolder)
        addView(row(chip("拍照") { callback.onCapture() }, chip("清空") { callback.onClear() }))
        addView(row(
            chip("解题") { callback.onSolve() },
            chip("朗读") { callback.onRead() },
            chip("复制") { callback.onCopy() },
        ))
        addView(row(chip("答对了") { callback.onMarkCorrect() }, chip("答错了") { callback.onMarkWrong() }))
        addView(chip("打开一起作业") { callback.onOpenApp() })
        addView(
            answerScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).also {
                it.weight = 1f
                it.topMargin = d(6)
            }
        )
    }

    private val container = FrameLayout(context)

    private var expanded = false
    private var lastAnswer: String? = null

    init {
        addView(container)
        container.addView(fabView, FrameLayout.LayoutParams(d(64), d(64)))
        setOnTouchListener(object : OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX.toInt()
                        startY = event.rawY.toInt()
                        moved = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.toInt() - startX
                        val dy = event.rawY.toInt() - startY
                        if (abs(dx) > 4 || abs(dy) > 4) {
                            moved = true
                            callback.onMove(dx, dy)
                        }
                    }

                    MotionEvent.ACTION_UP -> if (!moved) v.performClick()
                }
                return true
            }
        })
    }

    /** 展开 / 收起。 */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        container.removeAllViews()
        if (value) {
            container.addView(panelView, FrameLayout.LayoutParams(d(310), d(600)))
        } else {
            container.addView(fabView, FrameLayout.LayoutParams(d(64), d(64)))
        }
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
            answerView.text = "正在让 AI 看题…"
        } else if (lastAnswer != null) {
            answerView.text = lastAnswer
        }
    }

    fun setAnswer(text: String) {
        lastAnswer = text
        answerView.text = text
    }

    fun setError(message: String) {
        lastAnswer = null
        answerView.text = "出错了：$message"
    }

    fun currentAnswer(): String = lastAnswer.orEmpty()

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
        ).also { it.setBackgroundColor(0x33FFFFFF) }
    }

    private fun textView(text: String, sizeSp: Float, bold: Boolean, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun chip(text: String, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(d(6), d(9), d(6), d(9))
            setBackgroundDrawable(roundBackground(0x33FFFFFF, 12f))
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
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
