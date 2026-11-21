package com.qiqi.floatshow.overlay

import android.app.Service
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.qiqi.floatshow.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 面板与球的状态, 交回 Service 持久化 */
data class PanelState(
    val panel: PanelGeometry,
    val ball: PanelGeometry,
    val expanded: Boolean,
    val toolbarAtBottom: Boolean
)

/**
 * 悬浮面板的窗口层. 两个窗口:
 *  · 球窗口: 40dp 白底圆, 内嵌应用图标, 单击展开
 *  · 面板窗口: 无圆角, 工具栏 + 内容区 + 四边三角手柄
 *
 * 全部用原生 View 实现, 不走 Compose: 拖动缩放是每帧改窗口参数, Compose 的重组在这里是纯开销.
 *
 * 跟手收起沿用成熟做法: 面板窗口只设 INVISIBLE 而不是移除, 因为移除正在接收触摸的 View
 * 会让系统发 CANCEL, 手指一抬起拖动就断. 球另开一个窗口, 触摸流仍留在面板窗口里.
 */
class PanelWindow(
    private val service: Service,
    private val initialPanel: PanelGeometry,
    private val initialBall: PanelGeometry,
    private val expandedInitially: Boolean,
    private val toolbarAtBottomInitially: Boolean,
    private val ballSizePx: Int,
    private val controlSizePx: Int,
    private val minSizePx: Int,
    private val onState: (PanelState) -> Unit,
    private val onClose: () -> Unit
) {

    private val wm = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    /** 面板几何(px). 收起成球时仍然保留, 再次展开就按它还原 */
    private var panel = initialPanel
    /** 球几何(px), 与面板位置分开存 */
    private var ball = initialBall
    private var expanded = expandedInitially
    private var toolbarAtBottom = toolbarAtBottomInitially

    private var ballParams: WindowManager.LayoutParams? = null
    private var ballView: BallView? = null

    private var panelParams: WindowManager.LayoutParams? = null
    private var panelView: FrameLayout? = null
    private var bar: FrameLayout? = null
    private var barParams: FrameLayout.LayoutParams? = null
    private var contentView: View? = null
    private var contentParams: FrameLayout.LayoutParams? = null
    private var titleView: TextView? = null
    private var minimizeView: ImageView? = null
    private var closeView: ImageView? = null

    private val cornerGrips = mutableListOf<View>()
    private val edgeGrips = mutableListOf<View>()
    /** 手柄的布局参数预分配并复用, 缩放时每帧重建会产生大量临时对象 */
    private val edgeParams = mutableListOf<FrameLayout.LayoutParams>()
    private val cornerParams = mutableListOf<FrameLayout.LayoutParams>()

    // 拖动 / 缩放共用
    private var downRawX = 0f
    private var downRawY = 0f
    private var startPanelX = 0
    private var startPanelY = 0
    private var startPanelGeometry = initialPanel
    private var dragging = false
    private var followingPointer = false
    private var resizeGrip = PanelGrip.RIGHT_BOTTOM
    private var downOnBar = false
    private var resizeApplyPending = false
    private val resizeFrameCallback = Choreographer.FrameCallback {
        resizeApplyPending = false
        applyPanelGeometry()
    }

    val isShowing: Boolean get() = ballView != null || panelView != null

    // ── 展示 ────────────────────────────────────────────────

    fun show() {
        if (expanded) showPanelWindow(panel) else showBallWindow(ball)
    }

    private fun showBallWindow(geometry: PanelGeometry) {
        if (ballView != null) {
            ball = geometry
            return
        }
        val (screenWidth, screenHeight) = screenSize()
        ball = clampBall(geometry.copy(width = ballSizePx, height = ballSizePx), screenWidth, screenHeight)

        val view = BallView(service).apply {
            setOnTouchListener(ballTouchListener)
        }
        val params = windowParams(ballSizePx, ballSizePx, layoutNoLimits = false).apply {
            x = ball.x
            y = ball.y
        }
        runCatching { wm.addView(view, params) }
            .onFailure { return }
        ballView = view
        ballParams = params
    }

    private fun removeBallWindow() {
        val v = ballView ?: return
        v.setOnTouchListener(null)
        runCatching { wm.removeView(v) }
        ballView = null
        ballParams = null
    }

    private fun showPanelWindow(geometry: PanelGeometry) {
        if (panelView != null) {
            panel = geometry
            return
        }
        panel = geometry
        val (screenWidth, screenHeight) = screenSize()
        panel = PanelLayout.clamp(geometry, screenWidth, screenHeight)

        maxSurfacePx = min(screenWidth, screenHeight)

        val root = FrameLayout(service)
        // 子 View(手柄)允许画到容器之外
        root.clipChildren = false
        root.clipToPadding = false
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(panelColor())
            setStroke((density).toInt().coerceAtLeast(1), strokeColor())
        }

        val content = PanelContentHost.build(service)
        val contentLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = controlSizePx }
        root.addView(content, contentLp)
        contentView = content
        contentParams = contentLp

        val barView = buildBar()
        val barLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            controlSizePx
        ).apply { gravity = Gravity.TOP }
        root.addView(barView, barLp)
        bar = barView
        barParams = barLp

        // 边缘手柄: 只用来整体移动, 不改变尺寸.
        // 厚度取控制按钮的 2/3, 太薄会因为取整与手指宽度而"点不到边".
        val edgeThickness = (controlSizePx * 2 / 3).coerceAtLeast(1)
        repeat(3) {
            val edge = View(service)
            val lp = FrameLayout.LayoutParams(0, 0)
            edge.setOnTouchListener(dragListener)
            root.addView(edge, lp)
            edgeGrips += edge
            edgeParams += lp
        }
        // 角手柄: 缩放, 尺寸就是关闭按钮那一格, 压在边缘手柄之上
        repeat(3) {
            val corner = View(service)
            val lp = FrameLayout.LayoutParams(cornerSizePx, cornerSizePx)
            corner.setOnTouchListener(resizeListener)
            root.addView(corner, lp)
            cornerGrips += corner
            cornerParams += lp
        }
        edgeThicknessPx = edgeThickness

        val params = windowParams(panel.width, panel.height, layoutNoLimits = true).apply {
            x = panel.x
            y = panel.y
        }
        runCatching { wm.addView(root, params) }
            .onFailure { return }
        panelView = root
        panelParams = params

        setBarAtBottom(toolbarAtBottom, force = true)
    }

    private var edgeThicknessPx = 1

    /** 角手柄的方块边长: 关闭按钮的 1/2, 且完全在面板内侧 */
    private val cornerSizePx = (controlSizePx / 2).coerceAtLeast(1)
    /** 角手柄真正响应触摸的"线"粗: 关闭按钮的 1/8 */
    private val cornerLinePx = (controlSizePx / 8).coerceAtLeast(1)

    /** 面板最大边长, 展示时算一次 */
    private var maxSurfacePx = 1

    private fun removePanelWindow() {
        val v = panelView ?: return
        bar?.setOnTouchListener(null)
        edgeGrips.forEach { it.setOnTouchListener(null) }
        cornerGrips.forEach { it.setOnTouchListener(null) }
        cornerGrips.clear()
        edgeGrips.clear()
        edgeParams.clear()
        cornerParams.clear()
        runCatching { wm.removeView(v) }
        panelView = null
        panelParams = null
        bar = null
        barParams = null
        contentView = null
        contentParams = null
        titleView = null
        minimizeView = null
        closeView = null
    }

    fun remove() {
        removeBallWindow()
        removePanelWindow()
    }

    // ── 工具栏 ──────────────────────────────────────────────

    /** 工具栏: 标题 → 最小化 → 关闭 依次叠放, 具体位置见 [PanelToolbar] */
    private fun buildBar(): PanelToolbar {
        val barView = PanelToolbar(service, controlSizePx).apply {
            setBackgroundColor(barColor())
            setOnTouchListener(dragListener)
        }

        val title = TextView(service).apply {
            text = service.getString(R.string.panel_title)
            maxLines = 1
            // 不换行、不加省略号: 放不下的字符由上层按钮盖住, 而不是被截断
            setHorizontallyScrolling(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(inkColor())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val minimize = barButton(R.drawable.ic_panel_minimize, inkColor()) { collapseToBall() }
        val close = barButton(R.drawable.ic_panel_close, Color.argb(255, 220, 80, 80)) { onClose() }

        barView.addView(title, FrameLayout.LayoutParams(controlSizePx, controlSizePx))
        barView.addView(minimize, FrameLayout.LayoutParams(controlSizePx, controlSizePx))
        barView.addView(close, FrameLayout.LayoutParams(controlSizePx, controlSizePx))

        titleView = title
        minimizeView = minimize
        closeView = close
        return barView
    }

    /**
     * 工具栏按钮: 图标 20dp, 触发区是整块 controlSize. 用 ImageView 而不是 Compose 的 IconButton,
     * 因为这里的尺寸单位是精确像素, 不能跟着系统字体缩放.
     */
    private fun barButton(iconRes: Int, tint: Int, onClick: () -> Unit): ImageView {
        val size = controlSizePx
        val pad = (size - dp(20)) / 2
        return ImageView(service).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(tint)
            // 不透明底色: 按钮靠它盖住下层的标题与最小化
            setBackgroundColor(barColor())
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = false
            setOnClickListener { onClick() }
        }
    }

    /** 工具栏换到顶部/底部, 内容区与手柄跟着换位 */
    private fun setBarAtBottom(bottom: Boolean, force: Boolean = false) {
        if (!force && bottom == toolbarAtBottom) return
        toolbarAtBottom = bottom

        val root = panelView ?: return
        val barView = bar ?: return
        val barLp = barParams ?: return
        val contentLp = contentParams ?: return

        barLp.gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
        root.updateViewLayout(barView, barLp)
        contentLp.topMargin = if (bottom) 0 else controlSizePx
        contentView?.let { root.updateViewLayout(it, contentLp) }

        updateGripLayout()
    }

    /** 手柄位置: 边缘贴着不含工具栏的三条边, 角压在关闭按钮对面 */
    private fun updateGripLayout() {
        val root = panelView ?: return
        val t = edgeThicknessPx
        val c = cornerSizePx

        // 边缘: 工具栏所在的那条边不放手柄, 否则会压住最小化/关闭
        val edges = PanelLayout.edgeGrips(toolbarAtBottom)
        edgeGrips.forEachIndexed { index, view ->
            val grip = edges.getOrNull(index) ?: return@forEachIndexed
            val lp = edgeParams[index]
            when (grip) {
                // 一边定死厚度, 另一边 MATCH_PARENT: 窗口尺寸变化时手柄自动跟着长, 不用每帧重排
                PanelGrip.LEFT_EDGE -> {
                    lp.width = t
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                }
                PanelGrip.RIGHT_EDGE -> {
                    // 右侧边手柄必须让开工具栏那一段, 否则它会压在关闭/最小化按钮上,
                    // 按钮永远收不到触摸 —— 这就是"关闭几乎点不到"的原因
                    lp.width = t
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    if (toolbarAtBottom) {
                        lp.topMargin = 0
                        lp.bottomMargin = controlSizePx
                    } else {
                        lp.topMargin = controlSizePx
                        lp.bottomMargin = 0
                    }
                }
                PanelGrip.TOP_EDGE -> {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = t
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                PanelGrip.BOTTOM_EDGE -> {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = t
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                else -> {
                    lp.width = 0; lp.height = 0
                }
            }
            root.updateViewLayout(view, lp)
        }

        val corners = PanelLayout.cornerGrips(toolbarAtBottom)
        cornerGrips.forEachIndexed { index, view ->
            val grip = corners.getOrNull(index) ?: return@forEachIndexed
            val lp = cornerParams[index]
            val gravity = when (grip) {
                PanelGrip.LEFT_TOP -> Gravity.TOP or Gravity.START
                PanelGrip.RIGHT_TOP -> Gravity.TOP or Gravity.END
                PanelGrip.LEFT_BOTTOM -> Gravity.BOTTOM or Gravity.START
                PanelGrip.RIGHT_BOTTOM -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.START
            }
            if (lp.width != c || lp.height != c) {
                lp.width = c
                lp.height = c
            }
            if (lp.gravity != gravity) lp.gravity = gravity
            root.updateViewLayout(view, lp)
            view.tag = grip
        }
    }


    // ── 触摸: 球 ────────────────────────────────────────────

    private val ballTouchListener = View.OnTouchListener { _, event ->
        val params = ballParams ?: return@OnTouchListener false
        val view = ballView ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startPanelX = params.x
                startPanelY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    val (screenWidth, screenHeight) = screenSize()
                    ball = clampBall(
                        PanelGeometry(startPanelX + dx, startPanelY + dy, ballSizePx, ballSizePx),
                        screenWidth, screenHeight
                    )
                    params.x = ball.x
                    params.y = ball.y
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    expandFromBall()
                } else {
                    commit()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                commit()
            }
        }
        true
    }

    /** 以球心为锚点展开: 球在九宫格哪一格, 窗口就用同一格对应的角去对齐 */
    /**
     * 展开. 面板用它自己保存的位置与尺寸, 不再跟着球的位置走:
     * 球和面板各管各的位置, 展开只是把面板窗口开出来.
     */
    private fun expandFromBall() {
        val (screenWidth, screenHeight) = screenSize()
        val w = max(panel.width, minSizePx)
        val h = max(panel.height, minSizePx)
        var geometry = PanelLayout.clamp(PanelGeometry(panel.x, panel.y, w, h), screenWidth, screenHeight)
        if (!barVisibleAt(geometry)) {
            geometry = PanelLayout.clamp(
                PanelGeometry((screenWidth * 0.15f).toInt(), dp(8), w, h),
                screenWidth, screenHeight
            )
            setBarAtBottom(false, force = true)
        }

        removeBallWindow()
        expanded = true
        showPanelWindow(geometry)
        commit()
    }

    // ── 触摸: 面板拖动 / 缩放 ────────────────────────────────

    /** 工具栏与边缘共用: 只整体移动, 不改尺寸 */
    private val dragListener = View.OnTouchListener { view, event ->
        // 手指按在工具按钮上时直接把事件交给按钮, 否则拖动监听会把点击吃掉
        if (event.actionMasked == MotionEvent.ACTION_DOWN && hitsBarButton(event)) {
            return@OnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startPanelX = panel.x
                startPanelY = panel.y
                startPanelGeometry = panel
                dragging = false
                downOnBar = true
            }
            MotionEvent.ACTION_MOVE -> handleDragMove(event)
            MotionEvent.ACTION_UP -> {
                if (!dragging && !followingPointer) {
                    // 没拖动就是点了工具栏空白处, 不做任何事; 按钮有自己的点击处理
                } else if (followingPointer) {
                    removePanelWindow()
                    commit()
                } else {
                    commit()
                }
                dragging = false
                followingPointer = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                followingPointer = false
            }
        }
        true
    }

    /** DOWN 是否落在最小化/关闭按钮上: 用屏幕坐标比对, 不依赖布局层级 */
    private fun hitsBarButton(event: MotionEvent): Boolean {
        val targets = listOf(minimizeView, closeView)
        val loc = IntArray(2)
        for (target in targets) {
            val t = target ?: continue
            if (t.visibility != View.VISIBLE) continue
            t.getLocationOnScreen(loc)
            if (event.rawX >= loc[0] && event.rawX < loc[0] + t.width &&
                event.rawY >= loc[1] && event.rawY < loc[1] + t.height
            ) return true
        }
        return false
    }

    private fun handleDragMove(event: MotionEvent) {
        val dx = (event.rawX - downRawX).toInt()
        val dy = (event.rawY - downRawY).toInt()
        if (!dragging && !followingPointer && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            dragging = true
        }
        if (!dragging && !followingPointer) return

        if (followingPointer) {
            followBall(event.rawX, event.rawY)
            return
        }

        val (screenWidth, screenHeight) = screenSize()
        val moved = panel.copy(x = startPanelX + dx, y = startPanelY + dy)
        val clamped = PanelLayout.clamp(moved, screenWidth, screenHeight)

        // 翻转判断只在这个方向上有意义: 顶边进顶区翻到底部, 底边进底区翻回顶部
        val wantBottom = PanelLayout.nextToolbarAtBottom(toolbarAtBottom, clamped, screenHeight)
        if (wantBottom != toolbarAtBottom) setBarAtBottom(wantBottom)

        if (!barVisibleAt(clamped)) {
            // 工具栏完全看不见了: 收成球, 球跟着手指走
            followPointerAsBall(event.rawX, event.rawY)
            return
        }

        panel = clamped
        applyPanelGeometry()
    }

    /** 面板拖到工具栏不可见时, 先把自己藏起来(不移除), 再用球窗口跟手 */
    private fun followPointerAsBall(rawX: Float, rawY: Float) {
        panelView?.visibility = View.INVISIBLE
        showBallWindow(
            PanelGeometry(
                (rawX - ballSizePx / 2).toInt(),
                (rawY - ballSizePx / 2).toInt(),
                ballSizePx, ballSizePx
            )
        )
        followingPointer = true
    }

    /** 球跟手, 回到能放下的位置就自己展开 */
    private fun followBall(rawX: Float, rawY: Float) {
        val params = ballParams ?: return
        val view = ballView ?: return
        val (screenWidth, screenHeight) = screenSize()
        ball = clampBall(
            PanelGeometry(
                (rawX - ballSizePx / 2).toInt(),
                (rawY - ballSizePx / 2).toInt(),
                ballSizePx, ballSizePx
            ), screenWidth, screenHeight
        )
        params.x = ball.x
        params.y = ball.y
        runCatching { wm.updateViewLayout(view, params) }

        // 手指回到能让窗口放下来的位置, 面板回到它自己的位置(不跟球走)
        val target = PanelLayout.clamp(panel, screenWidth, screenHeight)
        if (!barVisibleAt(target)) return

        removeBallWindow()
        followingPointer = false
        panelView?.visibility = View.VISIBLE
        panel = target
        applyPanelGeometry()
        setBarAtBottom(PanelLayout.nextToolbarAtBottom(toolbarAtBottom, target, screenHeight))
    }

    /**
     * 角手柄只有按在贴着面板外沿的那两条线上才算数, 方块内部其余位置不响应.
     * DOWN 不在线上时返回 false, 触摸交给更下层的边缘手柄或内容.
     */
    private fun onCornerLine(view: View, event: MotionEvent): Boolean {
        val grip = view.tag as? PanelGrip ?: return false
        val t = cornerLinePx
        val nearVertical = if (grip.affectsLeft) event.x < t else event.x >= view.width - t
        val nearHorizontal = if (grip.affectsTop) event.y < t else event.y >= view.height - t
        return nearVertical || nearHorizontal
    }

    private val resizeListener = View.OnTouchListener { view, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !onCornerLine(view, event)) {
            return@OnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startPanelGeometry = panel
                resizeGrip = (view.tag as? PanelGrip) ?: PanelGrip.RIGHT_BOTTOM
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 缩放过程中不重新判断工具栏翻转, 也不重新做 5% 约束:
                // 每帧翻转会让手柄布局整体重排, 这就是"按住靠近工具栏的角更卡"的来源
                panel = PanelLayout.resize(
                    grip = resizeGrip,
                    start = startPanelGeometry,
                    dx = dx,
                    dy = dy,
                    minSize = minSizePx,
                    maxSize = maxSurfacePx
                )
                scheduleResizeGeometryApply()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                flushResizeGeometryApply()
                commit()
            }
        }
        true
    }

    /** 每个屏幕刷新帧最多更新一次悬浮窗, 保留该帧前最新的触摸几何. */
    private fun scheduleResizeGeometryApply() {
        if (resizeApplyPending) return
        resizeApplyPending = true
        Choreographer.getInstance().postFrameCallback(resizeFrameCallback)
    }

    /** 手指抬起前确保最后一次 MOVE 的几何已提交. */
    private fun flushResizeGeometryApply() {
        if (!resizeApplyPending) return
        Choreographer.getInstance().removeFrameCallback(resizeFrameCallback)
        resizeApplyPending = false
        applyPanelGeometry()
    }

    // ── 收起 / 提交 ──────────────────────────────────────────

    /** 最小化: 球落在最小化按钮原来的屏幕位置 */
    private fun collapseToBall() {
        if (!expanded) return
        val minimize = minimizeView
        val loc = IntArray(2)
        val minimizeX: Int
        val minimizeY: Int
        if (minimize != null) {
            minimize.getLocationOnScreen(loc)
            minimizeX = loc[0]
            minimizeY = loc[1]
        } else {
            minimizeX = panel.x + panel.width - controlSizePx * 2
            minimizeY = if (toolbarAtBottom) panel.y + panel.height - controlSizePx else panel.y
        }

        removePanelWindow()
        expanded = false
        showBallWindow(
            PanelGeometry(
                minimizeX + (controlSizePx - ballSizePx) / 2,
                minimizeY + (controlSizePx - ballSizePx) / 2,
                ballSizePx, ballSizePx
            )
        )
        commit()
    }

    private fun applyPanelGeometry() {
        val params = panelParams ?: return
        val view = panelView ?: return
        if (params.width == panel.width && params.height == panel.height &&
            params.x == panel.x && params.y == panel.y
        ) return
        params.width = panel.width
        params.height = panel.height
        params.x = panel.x
        params.y = panel.y
        // 只更新窗口尺寸. 手柄用 MATCH_PARENT 自适应, 标题的遮盖由静态边距决定,
        // 每帧重排这些子 View 正是缩放不如参考流畅的原因.
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun commit() {
        val (screenWidth, screenHeight) = screenSize()
        val clampedPanel = PanelLayout.clamp(panel, screenWidth, screenHeight)
        val clampedBall = clampBall(ball, screenWidth, screenHeight)
        panel = clampedPanel
        ball = clampedBall
        onState(PanelState(clampedPanel, clampedBall, expanded, toolbarAtBottom))
    }

    // ── 小工具 ──────────────────────────────────────────────

    private fun windowParams(
        width: Int,
        height: Int,
        layoutNoLimits: Boolean
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (layoutNoLimits) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        return params
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * 整屏尺寸(物理像素), 与状态栏/导航栏是否显示无关.
     * getRealSize 给的是 display 的完整尺寸, 所以下拉状态栏不会让这个值变.
     */
    private fun screenSize(): Pair<Int, Int> {
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return fallbackSize()
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return if (point.x > 0 && point.y > 0) point.x to point.y else fallbackSize()
    }

    private fun fallbackSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * 球的可用范围就是整块屏幕: 状态栏在不在都不影响.
     * 之前的实现给状态栏留了一条禁止进入的带子, 于是下拉状态栏时球会被系统挤下去.
     */
    private fun clampBall(geometry: PanelGeometry, screenWidth: Int, screenHeight: Int): PanelGeometry {
        val x = geometry.x.coerceIn(0, max(0, screenWidth - geometry.width))
        val y = geometry.y.coerceIn(0, max(0, screenHeight - geometry.height))
        return geometry.copy(x = x, y = y)
    }

    /** 以球心为锚点算面板位置: 球在哪一格, 面板就用那一格对应的角去对齐 */
    /** 工具栏(在顶部或底部)是否还能看见一点 */
    private fun barVisibleAt(geometry: PanelGeometry): Boolean {
        val barTop = if (toolbarAtBottom) geometry.y + geometry.height - controlSizePx else geometry.y
        val barBottom = barTop + controlSizePx
        // 用整屏高度判断, 不用 displayMetrics.heightPixels: 后者会随状态栏显隐变化
        val screenHeight = screenSize().second
        return barBottom > 0 && barTop < screenHeight
    }

    private fun panelColor(): Int {
        val night = (service.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.argb(240, 24, 24, 26) else Color.argb(245, 252, 252, 252)
    }

    /** 工具栏与按钮用不透明底色, 才能做到"后面的盖住前面的" */
    private fun barColor(): Int {
        val night = (service.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.rgb(36, 36, 38) else Color.rgb(246, 246, 246)
    }

    private fun strokeColor(): Int {
        val night = (service.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.argb(90, 255, 255, 255) else Color.argb(40, 0, 0, 0)
    }

    private fun inkColor(): Int {
        val night = (service.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.argb(255, 230, 230, 230) else Color.argb(255, 40, 40, 40)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
