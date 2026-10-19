package com.clearscreen.app.data.repository

import com.clearscreen.app.data.db.InterceptDao
import com.clearscreen.app.data.db.InterceptRecord
import kotlinx.coroutines.flow.Flow

class StatsRepository(private val interceptDao: InterceptDao) {

    val allRecords: Flow<List<InterceptRecord>> = interceptDao.getAllRecordsFlow()
    val totalInterceptCount: Flow<Int?> = interceptDao.getTotalInterceptCountFlow()
    val totalAppsCount: Flow<Int> = interceptDao.getTotalAppsCountFlow()

    suspend fun recordIntercept(packageName: String, appName: String, isJump: Boolean = false) {
        interceptDao.addIntercept(packageName, appName, isJump)
    }

    suspend fun clearAll() {
        interceptDao.clearAll()
    }
}
