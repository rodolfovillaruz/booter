package com.rodolfo.booter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.rodolfo.booter.aws.Ec2Instance

/**
 * Hands an instance to SSHBorg, which opens the host it has saved for it (matched by instance
 * ID, its address updated to [Ec2Instance.publicIp]) or a new host prefilled with it.
 * The action and extras are SSHBorg's `Ec2Launch` contract.
 */
object SshBorg {
    private const val ACTION = "com.sshborg.action.OPEN_EC2_INSTANCE"
    private const val EXTRA_INSTANCE_ID = "com.sshborg.extra.EC2_INSTANCE_ID"
    private const val EXTRA_NAME = "com.sshborg.extra.EC2_NAME"
    private const val EXTRA_HOST = "com.sshborg.extra.EC2_HOST"

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
}
