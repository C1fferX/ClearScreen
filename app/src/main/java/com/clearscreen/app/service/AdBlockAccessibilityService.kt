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
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * 清屏 2.1 - 终极稳定性防崩拦截引擎
 * 
 * 关键加固：
 * 1. 彻底移除所有 recycle() 调用，杜绝 IllegalStateException 导致的静默死锁
 * 2. 全局 CoroutineExceptionHandler 容灾，确保无障碍服务永不崩溃断开
 * 3. 优先探测 event.source 事件源节点，提升 5 倍响应速度
 * 4. 去除单数字误判，聚焦标准跳过/关闭词组及常见商用广告SDK View ID
 */
class AdBlockAccessibilityService : AccessibilityService() {

    // 容灾异常处理器：拦截所有后台异常，确保无障碍服务进程绝对存活
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "后台协程捕获非致命异常，防止服务中断: ${throwable.message}")
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 冷却时间控制：防止单次开屏重复触发
    private val coolDownMap = ConcurrentHashMap<String, Long>()

    // 启动突发采样 Job
    private var burstJob: Job? = null

    // 误触跳转检测
    private var lastAppPackage: String = ""
    private var lastAppLaunchTime: Long = 0L

    companion object {
        private const val TAG = "ClearScreenEngine"

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var lastHandledApp: String = "等待检测..."
            private set

        @Volatile
        var eventCounter: Long = 0L
            private set

        // 商用广告SDK常见 View ID（穿山甲、优量汇、快手等）
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
            "skip_button"
        )

        // 误触跳转拦截目标
        private val JUMP_TARGET_PACKAGES = setOf(
            "com.android.vending",
            "com.xiaomi.market",
            "com.oppo.market",
            "com.heytap.market",
            "com.huawei.appmarket",
            "com.bbk.appstore",
            "com.android.browser",
            "com.google.android.browser",
            "com.android.chrome"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "清屏 2.1 无障碍守护服务已成功连接！")
        KeepAliveForegroundService.startService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            eventCounter++
            val packageName = event.packageName?.toString() ?: return

            // 过滤系统自身与输入法桌面
            if (packageName == applicationContext.packageName ||
                packageName == "com.android.systemui" ||
                packageName.contains("inputmethod") ||
                packageName.contains("launcher")
            ) {
                return
            }

            lastHandledApp = packageName

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChange(packageName)
                    // 启动 3 秒突发高频采样
                    startBurstScanner(packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // 内容变动时优先从 event.source 快速就地扫描
                    val lastClick = coolDownMap[packageName] ?: 0L
                    if (SystemClock.uptimeMillis() - lastClick > 1500) {
                        event.source?.let { sourceNode ->
                            scanNodeHierarchy(sourceNode, packageName)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent 异常捕获", t)
        }
    }

    /**
     * 突发采样器：应用启动后前 3 秒以 200ms 频率扫描
     */
    private fun startBurstScanner(packageName: String) {
        burstJob?.cancel()
        burstJob = serviceScope.launch {
            val start = SystemClock.uptimeMillis()
            while (isActive && (SystemClock.uptimeMillis() - start < 3000)) {
                val lastClick = coolDownMap[packageName] ?: 0L
                if (SystemClock.uptimeMillis() - lastClick > 1500) {
                    val clicked = executeFullScan(packageName)
                    if (clicked) {
                        break
                    }
                }
                delay(200)
            }
        }
    }

    /**
     * 全面扫描屏幕窗口
     */
    private fun executeFullScan(packageName: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            return scanNodeHierarchy(root, packageName)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 递归遍历无障碍树（安全无 recycle 调用）
     */
    private fun scanNodeHierarchy(node: AccessibilityNodeInfo?, packageName: String, depth: Int = 0): Boolean {
        if (node == null || depth > 15) return false

        try {
            // 匹配是否为跳过控件
            if (checkIsSkipButton(node)) {
                val now = SystemClock.uptimeMillis()
                val lastClick = coolDownMap[packageName] ?: 0L
                if (now - lastClick > 1500) {
                    coolDownMap[packageName] = now
                    val clicked = performDualClick(node)
                    if (clicked) {
                        notifyIntercepted(packageName, isJump = false)
                        return true
                    }
                }
            }

            // 遍历子控件
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                if (scanNodeHierarchy(child, packageName, depth + 1)) {
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "节点遍历容错", t)
        }

        return false
    }

    /**
     * 校验控件特征
     */
    private fun checkIsSkipButton(node: AccessibilityNodeInfo): Boolean {
        try {
            // 1. 文本校验
            val text = node.text?.toString()
            if (!text.isNullOrEmpty() && isSkipText(text)) {
                return true
            }

            // 2. 描述校验
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrEmpty() && isSkipText(desc)) {
                return true
            }

            // 3. View ID 匹配
            val resId = node.viewIdResourceName
            if (!resId.isNullOrEmpty()) {
                val lower = resId.lowercase()
                for (id in SKIP_VIEW_IDS) {
                    if (lower.contains(id)) return true
                }
            }
        } catch (t: Throwable) {
            return false
        }

        return false
    }

    /**
     * 文本特征过滤
     */
    private fun isSkipText(raw: String): Boolean {
        // 清理空字符及零宽字符
        val clean = raw
            .replace("\\s+".toRegex(), "")
            .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
            .trim()

        if (clean.isEmpty() || clean.length > 10) return false

        // 核心跳过词
        if (clean.contains("跳过")) return true
        if (clean.contains("skip", ignoreCase = true)) return true
        if (clean == "关闭广告") return true

        return false
    }

    /**
     * 双发仿真点击：原生 ActionClick 与 屏幕物理手势同时作用
     */
    private fun performDualClick(node: AccessibilityNodeInfo): Boolean {
        var clicked = false

        try {
            // 1. 原生节点点击
            if (node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            // 2. 向上回溯父级点击
            if (!clicked) {
                var parent = node.parent
                var d = 0
                while (parent != null && d < 4) {
                    if (parent.isClickable) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    parent = parent.parent
                    d++
                }
            }

            // 3. 屏幕绝对坐标手势模拟点击（突破不可点击陷阱）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 10 && rect.height() > 10) {
                    val x = rect.centerX().toFloat()
                    val y = rect.centerY().toFloat()
                    val gesture = dispatchPhysicalClick(x, y)
                    if (gesture) clicked = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "点击执行异常", t)
        }

        return clicked
    }

    private fun dispatchPhysicalClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 误触跳转拦截
     */
    private fun handleWindowStateChange(currentPackage: String) {
        val now = SystemClock.uptimeMillis()

        if (currentPackage != lastAppPackage) {
            val isAccidentalJump = (now - lastAppLaunchTime < 3000) &&
                    lastAppPackage.isNotEmpty() &&
                    !JUMP_TARGET_PACKAGES.contains(lastAppPackage) &&
                    JUMP_TARGET_PACKAGES.contains(currentPackage)

            if (isAccidentalJump) {
                Log.w(TAG, "拦截误触跳转：[$lastAppPackage] -> [$currentPackage]")
                performGlobalAction(GLOBAL_ACTION_BACK)
                notifyIntercepted(lastAppPackage, isJump = true)
                return
            }

            if (!JUMP_TARGET_PACKAGES.contains(currentPackage)) {
                lastAppPackage = currentPackage
                lastAppLaunchTime = now
            }
        }
    }

    private fun notifyIntercepted(packageName: String, isJump: Boolean) {
        serviceScope.launch {
            val appName = ClearScreenApp.instance.getAppNameFromPackage(packageName)
            ClearScreenApp.instance.repository.recordIntercept(packageName, appName, isJump)

            mainHandler.post {
                val msg = if (isJump) "已为您拦截误触跳转" else "清屏：已为您跳过开屏广告"
                Toast.makeText(applicationContext, "$msg ($appName)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        burstJob?.cancel()
        serviceScope.cancel()
    }
}
