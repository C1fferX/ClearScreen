package com.clearscreen.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 按应用记录的拦截统计实体
 */
@Entity(tableName = "intercept_records")
data class InterceptRecord(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val interceptCount: Int = 1,
    val lastInterceptTime: Long = System.currentTimeMillis(),
    val splashInterceptCount: Int = 1, // 开屏跳过次数
    val jumpInterceptCount: Int = 0    // 误触跳转拦截次数
)
