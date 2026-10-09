// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/data/db/GroupDao.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Copied unchanged on 2026-10-09.

package com.sshborg.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM host_groups ORDER BY name ASC")
    fun getAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM host_groups")
    suspend fun getAllOnce(): List<GroupEntity>

    @Query("SELECT * FROM host_groups WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity): Long

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("UPDATE host_groups SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    @Query("UPDATE host_groups SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)
}
