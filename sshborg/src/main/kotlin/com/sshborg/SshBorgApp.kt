// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/SshBorgApp.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Modified on 2026-10-09: cut down to the state the copied screens use.

package com.sshborg

import android.app.Application
import com.jcraft.jsch.JSch
import com.sshborg.data.AppPreferences
import com.sshborg.data.db.AppDatabase
import com.sshborg.data.ssh.SshDiagnostics
import com.sshborg.service.SessionManager
import com.sshborg.service.TransferManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * The application object the SSHBorg screens expect (they reach shared state through
 * `app as SshBorgApp`). Booter uses it as its own application class.
 */
open class SshBorgApp : Application() {

    val db by lazy { AppDatabase.getInstance(this) }
    val sessionManager = SessionManager()
    val transferManager by lazy { TransferManager(this) }
    val appPreferences by lazy { AppPreferences(this) }

    /**
     * Set just before the app opens a system picker (file to upload, backup, key file). Booter
     * has no app lock, so nothing consumes it yet; kept so the copied screens compile unchanged.
     */
    @Volatile private var pickerTrip = false

    fun allowPickerTrip() { pickerTrip = true }

    fun consumePickerTrip(): Boolean = pickerTrip.also { pickerTrip = false }

    override fun onCreate() {
        super.onCreate()
        // Android ships an old BouncyCastle without Ed25519/ECDSA-P521 support.
        // Remove it and register the current version so JSch key generation works
        // on all API levels (Ed25519 is only in Android's JCE from API 33+).
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        // Capture JSch's own diagnostics: an in-memory ring buffer so a dropped connection
        // can show a real cause, plus Logcat output in debug builds only.
        JSch.setLogger(SshDiagnostics)
    }
}
