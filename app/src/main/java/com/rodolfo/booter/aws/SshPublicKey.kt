package com.rodolfo.booter.aws

import java.security.MessageDigest
import java.util.Base64

/** An OpenSSH public key ("type base64 [comment]"), as SSHBorg hands it over and AWS stores it. */
class SshPublicKey private constructor(val type: String, private val blob: ByteArray) {

    /** "type base64", without a comment. */
    val line: String get() = "$type ${Base64.getEncoder().encodeToString(blob)}"

    /** ImportKeyPair only takes these two. */
    val awsAccepts: Boolean get() = type == "ssh-rsa" || type == "ssh-ed25519"

    /**
     * Whether [pair] holds this key. Its fingerprint is checked first: AWS gives an imported RSA
     * key the MD5 of the key (RFC 4716, "aa:bb:…") and an ED25519 key the base64 SHA-256 of it.
     * A key AWS created itself has a fingerprint of the private key instead, which can't be
     * worked out from here, so the public key AWS returns is compared next.
     */
    fun matches(pair: Ec2KeyPair): Boolean {
        val fingerprint = pair.fingerprint?.trim()
        if (fingerprint != null) {
            if (fingerprint.equals(md5Fingerprint(), ignoreCase = true)) return true
            if (fingerprint.trimEnd('=') == sha256Fingerprint().trimEnd('=')) return true
        }
        val stored = pair.publicKey?.let(::parse) ?: return false
        return stored.type == type && stored.blob.contentEquals(blob)
    }

    private fun md5Fingerprint(): String =
        MessageDigest.getInstance("MD5").digest(blob).joinToString(":") { "%02x".format(it) }

    private fun sha256Fingerprint(): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    companion object {
        /** Null when [line] isn't an OpenSSH public key. */
        fun parse(line: String): SshPublicKey? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2 || !parts[0].startsWith("ssh-") && !parts[0].startsWith("ecdsa-")) return null
            val blob = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return null
            return if (blob.isEmpty()) null else SshPublicKey(parts[0], blob)
        }
    }
}
