package com.rodolfo.booter.aws

private val OPENSSH_PUBLIC_KEY = Regex(
    "^(ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|" +
        "sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/]+={0,2}( .*)?$",
)

/** Whether [line] looks like an OpenSSH public key: "type base64 [comment]". */
fun isOpenSshPublicKey(line: String): Boolean = OPENSSH_PUBLIC_KEY.matches(line.trim())

/**
 * User data that has cloud-init add [keys] to the default user's authorized_keys on first boot,
 * next to the key pair. Each key is single-quoted, the YAML style with no escapes besides ''.
 */
fun cloudConfig(keys: List<String>): String = buildString {
    appendLine("#cloud-config")
    appendLine("ssh_authorized_keys:")
    keys.forEach { appendLine("  - '${it.trim().replace("'", "''")}'") }
}
