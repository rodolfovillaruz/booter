// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/data/db/SshKeyEntity.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Copied unchanged on 2026-10-09.

package com.sshborg.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Key type: RSA, ECDSA, ED25519 */
    val keyType: String,
    /**
     * Plain-text PEM private key, never encrypted with a passphrase: an imported key is unlocked
     * once at import and stored unlocked, so nothing has to keep the passphrase. Empty string
     * when [encryptedBlob] is set. Use [com.sshborg.data.KeystoreManager.getPrivateKeyPem].
     */
    val privateKeyPem: String = "",
    /** OpenSSH public key string (e.g. "ssh-ed25519 AAAA...") */
    val publicKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** AES-GCM blob (Base64 IV||ciphertext) set when Keystore encryption is enabled. */
    val encryptedBlob: String? = null,
)
