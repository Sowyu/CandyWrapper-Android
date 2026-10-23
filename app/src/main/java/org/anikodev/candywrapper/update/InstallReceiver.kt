package org.anikodev.candywrapper.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.anikodev.candywrapper.CandyApp

/** Where PackageInstaller reports the session [Updater] committed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as CandyApp).graph.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return updater.installFailed("Android offered no install confirmation.")
                // ponytail: assumes the app is still in front, as it is right after the tap; if Android blocks the start, the banner offers a retry.
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    updater.installFailed("Android could not show the install confirmation. Tap Install again.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // This process is being replaced by the new build.
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.installFailed("Install cancelled.")
            else -> updater.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed.")
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
