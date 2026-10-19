package com.clearscreen.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clearscreen.app.ClearScreenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 清屏 - 核心无障碍拦截服务
 * 
 * 职责：
 * 1. 实时监听应用窗口变化事件
 * 2. 毫秒级识别开屏广告的“跳过/Skip/关闭”按钮并自动点击
 * 3. 识别开屏3秒内的误触跳转（跳转到第三方浏览器或应用商店），自动模拟返回键拦截
 * 4. 统计拦截数据并存入本地 Room 数据库
 */
class AdBlockAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 防重复点击冷却时间表 (PackageName -> 记录时间戳)
    private val clickCoolDownMap = ConcurrentHashMap<String, Long>()

    // 最近启动的应用包名与时间戳，用于识别误触跳转
    private var lastAppPackage: String = ""
    private var lastAppLaunchTime: Long = 0L

    companion object {
        private const val TAG = "AdBlockService"
        var isServiceRunning = false
            private set

        // 常见开屏跳过关键词正则
        private val SKIP_TEXT_PATTERNS = listOf(
            Pattern.compile("^\\s*跳过\\s*$"),
            Pattern.compile("^\\s*跳过广告\\s*$"),
            Pattern.compile(".*\\d+\\s*[sS秒]?\\s*跳过.*"),
            Pattern.compile(".*跳过\\s*\\d+\\s*[sS秒]?.*"),
            Pattern.compile("^\\s*Skip\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*SKIP\\s*Ad\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*关闭广告\\s*$")
        )

        // 知名商业广告SDK的常见跳过View ID关键字
        private val SKIP_VIEW_ID_KEYWORDS = listOf(
            "tt_splash_skip_btn",
            "splash_skip_tv",
            "btn_skip",
            "skip_view",
            "ksad_splash_skip_right_view",
            "splash_skip",
            "skip_text"
        )

        // 常见误触跳转的目标（应用市场、下载器、通用浏览器）
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
        Log.i(TAG, "清屏无障碍拦截服务已就绪")

        // 自动拉起前台保活通知
        KeepAliveForegroundService.startService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 忽略自身以及系统级UI界面（如SystemUI、输入法、桌面Launcher）
        if (packageName == applicationContext.packageName ||
            packageName == "com.android.systemui" ||
            packageName.contains("inputmethod")
        ) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
                // 窗口状态变化时，立即扫描广告跳过节点
                scanAndClickSkipButton(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化频繁，过滤冷却时间后扫描
                val lastClickTime = clickCoolDownMap[packageName] ?: 0L
                if (SystemClock.uptimeMillis() - lastClickTime > 1500) {
                    scanAndClickSkipButton(packageName)
                }
            }
        }
    }

    /**
     * 处理窗口切换：检测开屏误触跳转其他App（如突然唤醒浏览器或应用市场）
     */
    private fun handleWindowStateChanged(currentPackage: String) {
        val now = SystemClock.uptimeMillis()

        if (currentPackage != lastAppPackage) {
            // 如果刚打开前一个应用不到 3000ms，就突然切换到了应用商店或浏览器，判定为广告误触跳转
            val isPotentialAccidentalJump = (now - lastAppLaunchTime < 3000) &&
                    lastAppPackage.isNotEmpty() &&
                    !JUMP_TARGET_PACKAGES.contains(lastAppPackage) &&
                    JUMP_TARGET_PACKAGES.contains(currentPackage)

            if (isPotentialAccidentalJump) {
                Log.w(TAG, "检测到由 [$lastAppPackage] 引起的误触跳转至 [$currentPackage]，执行全局返回拦截！")
                // 模拟按下返回键，直接拦截回到原应用
                performGlobalAction(GLOBAL_ACTION_BACK)

                // 记录误触跳转拦截统计
                val sourcePkg = lastAppPackage
                serviceScope.launch {
                    val appName = ClearScreenApp.instance.getAppNameFromPackage(sourcePkg)
                    ClearScreenApp.instance.repository.recordIntercept(sourcePkg, appName, isJump = true)
                }
                return
            }

            // 更新当前活跃应用及启动时间
            if (!JUMP_TARGET_PACKAGES.contains(currentPackage)) {
                lastAppPackage = currentPackage
                lastAppLaunchTime = now
            }
        }
    }

    /**
     * 深度优先搜索（DFS）查找并触发“跳过”操作
     */
    private fun scanAndClickSkipButton(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val skipNode = findSkipNode(rootNode)
            if (skipNode != null) {
                val now = SystemClock.uptimeMillis()
                val lastClick = clickCoolDownMap[packageName] ?: 0L
                if (now - lastClick > 1500) { // 1.5秒冷却时间，防止单次开屏重复计算与连续点击
                    clickCoolDownMap[packageName] = now

                    val clicked = executeClick(skipNode)
                    if (clicked) {
                        Log.i(TAG, "成功跳过应用 [$packageName] 的开屏广告！")
                        // 异步持久化统计
                        serviceScope.launch {
                            val appName = ClearScreenApp.instance.getAppNameFromPackage(packageName)
                            ClearScreenApp.instance.repository.recordIntercept(packageName, appName, isJump = false)
                        }
                    }
                }
                skipNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描广告节点异常: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 递归遍历无障碍树，寻找匹配跳过特征的节点
     */
    private fun findSkipNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. 检查文本内容
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && matchesSkipPattern(text)) {
            return node
        }

        // 2. 检查描述内容（contentDescription）
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && matchesSkipPattern(desc)) {
            return node
        }

        // 3. 检查 View ID
        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrEmpty()) {
            val lowerId = viewId.lowercase()
            for (keyword in SKIP_VIEW_ID_KEYWORDS) {
                if (lowerId.contains(keyword)) {
                    return node
                }
            }
        }

        // 4. 递归子节点
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSkipNode(child)
            if (result != null) {
                return result
            }
            child.recycle()
        }

        return null
    }

    /**
     * 判断文本是否属于跳过广告语义
     */
    private fun matchesSkipPattern(text: String): Boolean {
        // 过滤超长文本（跳过按钮通常字数极短，不大于12字）
        if (text.length > 12) return false

        for (pattern in SKIP_TEXT_PATTERNS) {
            if (pattern.matcher(text).matches()) {
                return true
            }
        }
        return false
    }

    /**
     * 执行点击：优先控件原生点击，无法点击时使用父控件点击或模拟手势坐标点击
     */
    private fun executeClick(targetNode: AccessibilityNodeInfo): Boolean {
        // 1. 尝试直接点击当前节点
        if (targetNode.isClickable) {
            if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // 2. 尝试点击可点击的父节点（最多向上找3层）
        var parent = targetNode.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable) {
                val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                if (success) return true
                break
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
            depth++
        }

        // 3. 恶意不可点击或自定义绘制 View：采用坐标手势模拟点击 (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val x = rect.centerX().toFloat()
                val y = rect.centerY().toFloat()
                return dispatchClickGesture(x, y)
            }
        }

        return false
    }

    /**
     * 通过手势模拟点击屏幕坐标 (x, y)
     */
    private fun dispatchClickGesture(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.w(TAG, "清屏无障碍服务被系统中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        Log.i(TAG, "清屏无障碍服务已销毁")
    }
}
