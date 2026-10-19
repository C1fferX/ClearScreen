package com.clearscreen.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InterceptDao {

    @Query("SELECT * FROM intercept_records ORDER BY interceptCount DESC, lastInterceptTime DESC")
    fun getAllRecordsFlow(): Flow<List<InterceptRecord>>

    @Query("SELECT * FROM intercept_records WHERE packageName = :pkg LIMIT 1")
    suspend fun getRecordByPackage(pkg: String): InterceptRecord?

    @Query("SELECT SUM(interceptCount) FROM intercept_records")
    fun getTotalInterceptCountFlow(): Flow<Int?>

    @Query("SELECT COUNT(*) FROM intercept_records")
    fun getTotalAppsCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: InterceptRecord)

    @Update
    suspend fun update(record: InterceptRecord)

    @Transaction
    suspend fun addIntercept(packageName: String, appName: String, isJump: Boolean) {
        val existing = getRecordByPackage(packageName)
        val currentTime = System.currentTimeMillis()
        if (existing != null) {
            val updated = existing.copy(
                appName = appName,
                interceptCount = existing.interceptCount + 1,
                lastInterceptTime = currentTime,
                splashInterceptCount = if (!isJump) existing.splashInterceptCount + 1 else existing.splashInterceptCount,
                jumpInterceptCount = if (isJump) existing.jumpInterceptCount + 1 else existing.jumpInterceptCount
            )
            update(updated)
        } else {
            val newRecord = InterceptRecord(
                packageName = packageName,
                appName = appName,
                interceptCount = 1,
                lastInterceptTime = currentTime,
                splashInterceptCount = if (!isJump) 1 else 0,
                jumpInterceptCount = if (isJump) 1 else 0
            )
            insert(newRecord)
        }
    }

    @Query("DELETE FROM intercept_records")
    suspend fun clearAll()
}
