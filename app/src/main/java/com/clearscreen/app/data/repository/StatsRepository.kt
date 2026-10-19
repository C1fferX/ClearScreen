package com.clearscreen.app.data.repository

import com.clearscreen.app.data.db.InterceptDao
import com.clearscreen.app.data.db.InterceptRecord
import kotlinx.coroutines.flow.Flow

class StatsRepository(private val interceptDao: InterceptDao) {

    val allRecords: Flow<List<InterceptRecord>> = interceptDao.getAllRecordsFlow()
    val totalInterceptCount: Flow<Int?> = interceptDao.getTotalInterceptCountFlow()
    val totalAppsCount: Flow<Int> = interceptDao.getTotalAppsCountFlow()

    suspend fun recordIntercept(packageName: String, appName: String, isJump: Boolean = false) {
        val existing = interceptDao.getRecordByPackage(packageName)
        val currentTime = System.currentTimeMillis()
        if (existing != null) {
            val updated = existing.copy(
                appName = appName,
                interceptCount = existing.interceptCount + 1,
                lastInterceptTime = currentTime,
                splashInterceptCount = if (!isJump) existing.splashInterceptCount + 1 else existing.splashInterceptCount,
                jumpInterceptCount = if (isJump) existing.jumpInterceptCount + 1 else existing.jumpInterceptCount
            )
            interceptDao.update(updated)
        } else {
            val newRecord = InterceptRecord(
                packageName = packageName,
                appName = appName,
                interceptCount = 1,
                lastInterceptTime = currentTime,
                splashInterceptCount = if (!isJump) 1 else 0,
                jumpInterceptCount = if (isJump) 1 else 0
            )
            interceptDao.insert(newRecord)
        }
    }

    suspend fun clearAll() {
        interceptDao.clearAll()
    }
}
