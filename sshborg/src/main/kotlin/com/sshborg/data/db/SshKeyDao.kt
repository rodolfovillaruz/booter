// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/data/db/SshKeyDao.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Copied unchanged on 2026-10-09.

package com.sshborg.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY label ASC")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys ORDER BY label ASC")
    suspend fun getAllOnce(): List<SshKeyEntity>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: SshKeyEntity): Long

    @Delete
    suspend fun delete(key: SshKeyEntity)
}
