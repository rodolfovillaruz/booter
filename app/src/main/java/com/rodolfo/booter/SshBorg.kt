package com.rodolfo.booter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.rodolfo.booter.aws.Ec2Instance

/**
 * Hands an instance to SSHBorg, which opens the host it has saved for it (matched by instance
 * ID, its address updated to [Ec2Instance.publicIp]) or a new host prefilled with it.
 * The action and extras are SSHBorg's `Ec2Launch` contract; picking a key is its
 * `PublicKeyPickActivity` one.
 */
object SshBorg {
    private const val ACTION = "com.sshborg.action.OPEN_EC2_INSTANCE"
    private const val EXTRA_INSTANCE_ID = "com.sshborg.extra.EC2_INSTANCE_ID"
    private const val EXTRA_NAME = "com.sshborg.extra.EC2_NAME"
    private const val EXTRA_HOST = "com.sshborg.extra.EC2_HOST"
    private const val ACTION_PICK_KEY = "com.sshborg.action.PICK_PUBLIC_KEY"
    private const val EXTRA_PUBLIC_KEY = "com.sshborg.extra.PUBLIC_KEY"
    private const val EXTRA_KEY_LABEL = "com.sshborg.extra.KEY_LABEL"

    /** False when SSHBorg isn't installed. [instance] must have a public IP. */
    fun open(context: Context, instance: Ec2Instance): Boolean {
        val intent = Intent(ACTION)
            .putExtra(EXTRA_INSTANCE_ID, instance.id)
            .putExtra(EXTRA_NAME, instance.displayName)
            .putExtra(EXTRA_HOST, requireNotNull(instance.publicIp))
            // Into SSHBorg's own task, reusing its open window (it is singleTop).
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Asks SSHBorg for one of its keys; start it for a result and read that with [pickedKey]. */
    fun pickKeyIntent(): Intent = Intent(ACTION_PICK_KEY)

    /** The public key line and label SSHBorg returned, or null when the user backed out. */
    fun pickedKey(result: ActivityResult): Pair<String, String>? {
        if (result.resultCode != Activity.RESULT_OK) return null
        val data = result.data ?: return null
        val key = data.getStringExtra(EXTRA_PUBLIC_KEY) ?: return null
        return key to (data.getStringExtra(EXTRA_KEY_LABEL) ?: "SSHBorg key")
    }
}
