package com.clearscreen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.clearscreen.app.ClearScreenApp
import com.clearscreen.app.R
import com.clearscreen.app.databinding.ActivityMainBinding
import com.clearscreen.app.service.AdBlockAccessibilityService
import com.clearscreen.app.service.KeepAliveForegroundService
import com.clearscreen.app.util.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(ClearScreenApp.instance.repository)
    }

    private val adapter = StatsAdapter()

    // 申请通知权限（Android 13+）
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                if (AdBlockAccessibilityService.isServiceRunning) {
                    KeepAliveForegroundService.startService(this)
                }
            } else {
                Toast.makeText(this, "未授予定时通知权限，保活通知可能无法在前台展示", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupToolbar() {
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_reset -> {
                    showResetConfirmDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        binding.rvAppStats.layoutManager = LinearLayoutManager(this)
        binding.rvAppStats.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnBatteryOpt.setOnClickListener {
            PermissionHelper.requestIgnoreBatteryOptimization(this)
        }

        binding.btnAutoStart.setOnClickListener {
            PermissionHelper.openOEMAutoStartSetting(this)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 观察应用拦截记录列表
                launch {
                    viewModel.records.collectLatest { list ->
                        adapter.submitList(list)
                        binding.tvEmptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvAppStats.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    }
                }

                // 观察总拦截次数
                launch {
                    viewModel.totalInterceptCount.collectLatest { count ->
                        binding.tvTotalInterceptCount.text = count.toString()
                    }
                }

                // 观察覆盖应用数
                launch {
                    viewModel.totalAppsCount.collectLatest { appCount ->
                        binding.tvTotalAppsCovered.text = "累计守护 $appCount 款应用"
                    }
                }
            }
        }
    }

    /**
     * 刷新无障碍服务与保活状态指示灯
     */
    private fun updateServiceStatus() {
        val isAccessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        val isBatteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(this)

        if (isAccessibilityEnabled) {
            binding.viewStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.success))
            binding.tvServiceStatus.text = "无障碍服务：运行中（守护开启）"
            binding.btnToggleAccessibility.text = "已启用"
            binding.btnToggleAccessibility.isEnabled = false
            binding.tvServiceDesc.text = "无障碍运行正常 | 事件心跳计数: ${AdBlockAccessibilityService.eventCounter} | 最近检测: ${AdBlockAccessibilityService.lastHandledApp}"

            // 确保前台服务运行中
            KeepAliveForegroundService.startService(this)
        } else {
            binding.viewStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.danger))
            binding.tvServiceStatus.text = "无障碍服务：未开启"
            binding.btnToggleAccessibility.text = "去开启"
            binding.btnToggleAccessibility.isEnabled = true
            binding.tvServiceDesc.text = "需开启无障碍服务以实时检测跳过按钮并自动点击。Android 13+ 需先在应用信息允许受限制设置。"
        }

        if (isBatteryIgnored) {
            binding.btnBatteryOpt.text = "电池白名单：已加入"
            binding.btnBatteryOpt.isEnabled = false
        } else {
            binding.btnBatteryOpt.text = "申请电池白名单"
            binding.btnBatteryOpt.isEnabled = true
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("确认清空统计？")
            .setMessage("清空后所有应用的累计拦截次数将被重置为0。")
            .setPositiveButton("清空") { _, _ ->
                viewModel.clearStats()
                Toast.makeText(this, "统计已重置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
