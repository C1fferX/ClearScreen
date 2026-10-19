# 清屏 (ClearScreen) - 开屏与误触跳转广告拦截器

一款基于 Android **无障碍服务 (AccessibilityService)** 开发的轻量级广告拦截与统计工具，支持毫秒级识别并点击开屏“跳过”按钮，并在误触跳转至浏览器或应用市场时自动拦截返回，按应用记录并可视化展示拦截统计。

---

## 核心实现特性

1. **开屏广告智能拦截**：
   - 深度优先搜索（DFS）遍历节点树，毫秒级模糊匹配“跳过”、“Skip”、“关闭”等中英文关键词与倒计时正则。
   - 适配知名广告联盟常见 View ID（穿山甲、优量汇、快手等）。
   - 双重点击保障：优先调用原生 `ACTION_CLICK`，若节点被开发者恶意设为不可点击，自动回溯父布局或采用 Android 7.0+ `dispatchGesture` 模拟真实坐标点击。
   - 1.5 秒冷却防抖机制，杜绝死循环连续点击。

2. **开屏误触跳转拦截**：
   - 实时比对窗口状态切换。若在打开应用 3 秒内突然切换至外部下载渠道（主流浏览器、各厂商应用商店），判定为恶意误触或摇一摇跳转，立即触发全局返回键 `GLOBAL_ACTION_BACK` 拦截返回。

3. **按应用维度持久化统计**：
   - 基于 Jetpack Room 数据库存储：记录包名、应用名称、累计拦截数、开屏跳过数、误触拦截数及最后拦截时间。
   - Material Design 3 风格 UI，支持下拉查看各应用排行，提供一键重置功能。

4. **系统级保活策略**：
   - 前台常驻通知（Foreground Service + `specialUse`），大幅降低 Low Memory Killer (LMK) 查杀权重。
   - 监听开机自启 (`RECEIVE_BOOT_COMPLETED`) 与解锁唤醒 (`USER_PRESENT`)。
   - 内置国内主流厂商（小米、华为、OPPO、vivo、魅族）自启动与后台锁定引导。

---

## 编译与运行

1. 使用 **Android Studio Iguana / Jellyfish 或更高版本** 打开此目录 (`d:\agy\Android`)。
2. 配置 JDK 17 及以上。
3. 点击 **Sync Project with Gradle Files**。
4. 连接 Android 设备（或模拟器），点击 **Run 'app'** 即可直接编译安装。
