package com.clearscreen.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM intercept_records")
    suspend fun clearAll()
}
