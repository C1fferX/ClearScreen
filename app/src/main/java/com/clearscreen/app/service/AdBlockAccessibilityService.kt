package com.clearscreen.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.clearscreen.app.ClearScreenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 清屏 2.0 - 高性能无障碍拦截引擎
 * 
 * 核心升级：
 * 1. 3.5秒动态高频采样轮询（Burst Scanner）：解决商业广告异步延迟加载导致的漏检
 * 2. 多窗口深度检索（Multi-Window）：兼顾弹窗遮罩型悬浮广告
 * 3. 文本多重降噪清洗：剔除零宽字符、全角空格等防外挂干扰
 * 4. 双通道真实仿真点击：原生 ActionClick 与 80ms 物理手势模拟双发，穿透"只监听Touch不监听Click"的恶意SDK
 * 5. 可见性与真实几何尺寸校验：排除不可见诱饵节点
 */
class AdBlockAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 针对每个应用的防重复冷却时间表 (PackageName -> 记录时间戳)
    private val clickCoolDownMap = ConcurrentHashMap<String, Long>()

    // 每个应用前台启动时的突发轮询 Job
    private var activeBurstJob: Job? = null
    private var currentForegroundPackage: String = ""

    // 记录前一个活跃应用及启动时间，用于误触跳转拦截
    private var lastAppPackage: String = ""
    private var lastAppLaunchTime: Long = 0L

    companion object {
        private const val TAG = "ClearScreenEngine"
        var isServiceRunning = false
            private set

        // 知名商业广告SDK的常见跳过View ID关键字列表（穿山甲、优量汇、快手等）
        private val SKIP_VIEW_IDS = listOf(
            "tt_splash_skip_btn",
            "splash_skip_tv",
            "btn_skip",
            "skip_view",
            "ksad_splash_skip_right_view",
            "splash_skip",
            "skip_text",
            "iv_skip",
            "tv_skip",
            "gdt_splash_skip",
            "skip_button",
            "skip_layout"
        )

        // 常见误触跳转的目标应用（各大厂商应用市场、通用浏览器）
        private val JUMP_TARGET_PACKAGES = setOf(
            "com.android.vending",          // Google Play
            "com.xiaomi.market",            // 小米应用商店
            "com.oppo.market",              // OPPO软件商店
            "com.heytap.market",            // 欢太/OPPO/一加
            "com.huawei.appmarket",         // 华为应用市场
            "com.bbk.appstore",             // vivo应用商店
            "com.android.browser",          // 系统自带浏览器
            "com.google.android.browser",
            "com.android.chrome"            // Chrome
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "清屏 2.0 拦截引擎已就绪")
        KeepAliveForegroundService.startService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 忽略自身、输入法及系统桌面核心组件
        if (packageName == applicationContext.packageName ||
            packageName == "com.android.systemui" ||
            packageName.contains("inputmethod") ||
            packageName.contains("launcher")
        ) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
                // 窗口刚切换时立即执行一次探测
                triggerInstantScan(packageName)
                // 同时开启持续 3.5 秒的突发高频采样器，对抗动态延迟加载广告
                startBurstScanner(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化时触发探测
                val lastClick = clickCoolDownMap[packageName] ?: 0L
                if (SystemClock.uptimeMillis() - lastClick > 1800) {
                    triggerInstantScan(packageName)
                }
            }
        }
    }

    /**
     * 突发采样器：在应用进入前台后的 3.5 秒内，以 150ms 的极高频密巡检屏幕，
     * 专治“在界面加载 0.5s ~ 1.5s 后才延迟弹出的开屏广告”
     */
    private fun startBurstScanner(packageName: String) {
        activeBurstJob?.cancel()
        currentForegroundPackage = packageName

        activeBurstJob = serviceScope.launch {
            val startTime = SystemClock.uptimeMillis()
            while (isActive && (SystemClock.uptimeMillis() - startTime < 3500)) {
                val lastClick = clickCoolDownMap[packageName] ?: 0L
                if (SystemClock.uptimeMillis() - lastClick > 1800) {
                    val hit = performComprehensiveScan(packageName)
                    if (hit) {
                        Log.i(TAG, "突发采样器命中并跳过广告，结束当前轮询")
                        break
                    }
                }
                delay(150)
            }
        }
    }

    private fun triggerInstantScan(packageName: String) {
        val lastClick = clickCoolDownMap[packageName] ?: 0L
        if (SystemClock.uptimeMillis() - lastClick > 1800) {
            serviceScope.launch {
                performComprehensiveScan(packageName)
            }
        }
    }

    /**
     * 多窗口深度检测主逻辑
     */
    private fun performComprehensiveScan(packageName: String): Boolean {
        // 1. 优先扫描当前活跃窗口
        rootInActiveWindow?.let { root ->
            try {
                if (findAndClickTarget(root, packageName)) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }

        // 2. 若主窗口未检测到，遍历所有可见交互窗口（应对弹窗、Dialog、Overlay 式开屏广告）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val allWindows = windows
                for (window in allWindows) {
                    val root = window.root ?: continue
                    try {
                        if (findAndClickTarget(root, packageName)) {
                            return true
                        }
                    } finally {
                        root.recycle()
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        return false
    }

    /**
     * 递归检索并尝试触发点击
     */
    private fun findAndClickTarget(node: AccessibilityNodeInfo, packageName: String): Boolean {
        if (!isNodeValidAndVisible(node)) {
            return false
        }

        // 1. 匹配该节点本身是否为“跳过”按钮
        if (isSkipNode(node)) {
            val now = SystemClock.uptimeMillis()
            clickCoolDownMap[packageName] = now
            val success = executeDualChannelClick(node)
            if (success) {
                onAdSuccessfullyIntercepted(packageName, isJump = false)
                return true
            }
        }

        // 2. 深度优先递归子节点
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClickTarget(child, packageName)
            child.recycle()
            if (found) {
                return true
            }
        }

        return false
    }

    /**
     * 节点有效性与可见性校验
     */
    private fun isNodeValidAndVisible(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (!node.isVisibleToUser) return false
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        // 排除面积为0或异常偏离屏幕的幽灵节点
        return rect.width() >= 10 && rect.height() >= 10
    }

    /**
     * 判断节点文本/ID是否为跳过特征
     */
    private fun isSkipNode(node: AccessibilityNodeInfo): Boolean {
        // 1. 检查文本
        node.text?.toString()?.let { raw ->
            if (matchesCleanedSkipText(raw)) return true
        }

        // 2. 检查描述 (contentDescription)
        node.contentDescription?.toString()?.let { raw ->
            if (matchesCleanedSkipText(raw)) return true
        }

        // 3. 检查 View ID
        node.viewIdResourceName?.let { viewId ->
            val lower = viewId.lowercase()
            for (idKeyword in SKIP_VIEW_IDS) {
                if (lower.contains(idKeyword)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 深度清洗文本并匹配
     */
    private fun matchesCleanedSkipText(rawText: String): Boolean {
        // 清除所有全角/半角空格、制表符、零宽字符（\u200B, \uFEFF等常见防脚本手段）
        val clean = rawText
            .replace("\\s+".toRegex(), "")
            .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
            .trim()

        if (clean.isEmpty() || clean.length > 15) return false

        // 包含“跳过”关键词
        if (clean.contains("跳过")) {
            return true
        }

        // 包含英文“skip”
        if (clean.contains("skip", ignoreCase = true)) {
            return true
        }

        // 常见关闭语义（但排除纯系统对话框）
        if (clean == "关闭广告" || clean == "关闭") {
            return true
        }

        // 倒计时匹配：例如 "3s"、"5秒"（部分广告跳过按钮直接显示倒计时圆圈）
        if (clean.matches("^[0-9]s?$".toRegex()) || clean.matches("^[0-9]秒$".toRegex())) {
            return true
        }

        return false
    }

    /**
     * 双通道仿真点击：
     * 通道 1：原生 performAction(ACTION_CLICK) 与父级遍历
     * 通道 2：80ms 物理手势模拟点击绝对坐标（突破 onTouchListener 监听与不可点击外层陷阱）
     */
    private fun executeDualChannelClick(node: AccessibilityNodeInfo): Boolean {
        var clicked = false

        // 通道 1：控件原生点击
        if (node.isClickable) {
            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        if (!clicked) {
            // 向上检索 3 级可点击父容器
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isClickable) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    break
                }
                val grand = parent.parent
                parent.recycle()
                parent = grand
                depth++
            }
        }

        // 通道 2：获取实际屏幕物理坐标，发送物理手势（无论通道1是否返回成功，双发确保穿透）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()
                val gestureSuccess = dispatchRealisticClick(cx, cy)
                if (gestureSuccess) {
                    clicked = true
                }
            }
        }

        return clicked
    }

    /**
     * 模拟真实的屏幕点击动作（80ms 手指按压时长）
     */
    private fun dispatchRealisticClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 误触与恶意跳转检测拦截
     */
    private fun handleWindowStateChanged(currentPackage: String) {
        val now = SystemClock.uptimeMillis()

        if (currentPackage != lastAppPackage) {
            // 打开前一个 App 处于 3.5 秒内，突然跳转到了应用商店或浏览器
            val isAccidentalJump = (now - lastAppLaunchTime < 3500) &&
                    lastAppPackage.isNotEmpty() &&
                    !JUMP_TARGET_PACKAGES.contains(lastAppPackage) &&
                    JUMP_TARGET_PACKAGES.contains(currentPackage)

            if (isAccidentalJump) {
                Log.w(TAG, "拦截到误触跳转：[$lastAppPackage] -> [$currentPackage]，模拟全局返回！")
                performGlobalAction(GLOBAL_ACTION_BACK)
                onAdSuccessfullyIntercepted(lastAppPackage, isJump = true)
                return
            }

            if (!JUMP_TARGET_PACKAGES.contains(currentPackage)) {
                lastAppPackage = currentPackage
                lastAppLaunchTime = now
            }
        }
    }

    /**
     * 拦截成功处理：持久化数据库并弹出吐司
     */
    private fun onAdSuccessfullyIntercepted(packageName: String, isJump: Boolean) {
        serviceScope.launch {
            val appName = ClearScreenApp.instance.getAppNameFromPackage(packageName)
            ClearScreenApp.instance.repository.recordIntercept(packageName, appName, isJump)

            mainHandler.post {
                val tip = if (isJump) "已为您拦截误触跳转" else "清屏：已为您跳过广告"
                Toast.makeText(applicationContext, "$tip ($appName)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        activeBurstJob?.cancel()
        serviceScope.cancel()
    }
}
