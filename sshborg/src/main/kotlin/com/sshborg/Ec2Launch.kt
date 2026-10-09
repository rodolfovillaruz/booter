// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/Ec2Launch.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Modified on 2026-10-09: Booter calls it directly, so the intent parsing is gone.

package com.sshborg

import com.sshborg.data.db.HostDao
import com.sshborg.data.db.HostEntity

/**
 * An EC2 instance Booter opens: the instance ID, its Name tag, and the public IP it has right
 * now. A host linked to that instance ID keeps its pinned host key, so an address that now
 * belongs to some other server meets a host-key warning.
 */
data class Ec2Launch(val instanceId: String, val name: String, val host: String) {

    sealed interface Target {
        /** A saved host for this instance, already updated to the current address. */
        data class Saved(val host: HostEntity) : Target
        /** No saved host yet: open the editor prefilled with this request. */
        data class New(val launch: Ec2Launch) : Target
    }

    /**
     * Finds the host for this instance: the one linked to its ID, or else an unlinked host
     * already pointing at its current address, which gets linked now. A linked host whose
     * address changed is moved to the new one, carrying its pinned host key along.
     */
    suspend fun resolve(dao: HostDao): Target {
        val linked = dao.getByEc2InstanceId(instanceId)
        if (linked != null) {
            if (linked.hostname == host) return Target.Saved(linked)
            val moved = linked.copy(
                hostname = host,
                knownHostsEntry = linked.knownHostsEntry?.let { rehost(it, host) },
            )
            dao.upsert(moved)
            return Target.Saved(moved)
        }
        val sameAddress = dao.getUnlinkedByHostname(host)
        if (sameAddress != null) {
            val adopted = sameAddress.copy(ec2InstanceId = instanceId)
            dao.upsert(adopted)
            return Target.Saved(adopted)
        }
        return Target.New(this)
    }

    private companion object {
        /**
         * [line] ("host type key", as SshManager.buildKnownHostsLine writes it) re-pointed at
         * [newHost]. An instance keeps its host key across stops and starts, so the pin stays
         * valid at the new address; only the host field, "[host]:port" off port 22, changes.
         */
        fun rehost(line: String, newHost: String): String {
            val hostField = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            if (rest.isEmpty()) return line
            val newField = if (hostField.startsWith("[")) "[$newHost]${hostField.substringAfter(']')}" else newHost
            return "$newField $rest"
        }
    }
}
