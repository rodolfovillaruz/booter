package com.rodolfo.booter.data

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers "once instance X is stopped, boot it as size Y" across app restarts.
 * Keyed by instance ID, value is the target instance type.
 */
class PendingResizeStore(context: Context) {

    private val prefs = context.getSharedPreferences("pending_resizes", Context.MODE_PRIVATE)

    fun all(): Map<String, String> =
        prefs.all.mapNotNull { (id, size) -> (size as? String)?.let { id to it } }.toMap()

    fun put(instanceId: String, size: String) = prefs.edit { putString(instanceId, size) }

    fun remove(instanceId: String) = prefs.edit { remove(instanceId) }
}
