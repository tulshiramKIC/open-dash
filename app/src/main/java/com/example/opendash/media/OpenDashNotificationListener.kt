package com.example.opendash.media

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class OpenDashNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        android.util.Log.d("NotificationListener", "onNotificationPosted: pkg = " + sbn.packageName + ", category = " + notification.category)
        
        var hasAnswer = false
        var hasDecline = false
        notification.actions?.forEach { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            if (title.contains("answer") || title.contains("accept")) {
                hasAnswer = true
            }
            if (title.contains("decline") || title.contains("reject") ||
                title.contains("dismiss") || title.contains("hang") ||
                title.contains("end") || title.contains("ignore")
            ) {
                hasDecline = true
            }
        }

        val isCallStyle = if (android.os.Build.VERSION.SDK_INT >= 31) {
            notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("CallStyle") == true
        } else false
        val isCallCategory = notification.category == Notification.CATEGORY_CALL

        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase().orEmpty()
        val isDialing = text.contains("calling") || text.contains("ringing") || text.contains("dialing") || text.contains("connecting") ||
                        subText.contains("calling") || subText.contains("ringing") || subText.contains("dialing") || subText.contains("connecting")

        val pkg = sbn.packageName.lowercase()
        val isCallPkg = pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("whatsapp") || pkg.contains("telecom") || pkg.contains("telegram")

        val isCall = isCallCategory || isCallStyle || (hasAnswer && hasDecline) || (hasDecline && isDialing && isCallPkg)
        if (!isCall) return

        val incoming = hasAnswer
        val active = !hasAnswer && hasDecline

        android.util.Log.d("NotificationListener", "Parsed call state: incoming = " + incoming + ", active = " + active + ", dialing = " + isDialing)

        if (!incoming && !active) {
            CallInfoProvider.update(null)
            return
        }

        val (answer, decline) = extractActions(notification, incoming)
        val caller = callerName(notification).ifBlank { if (incoming) "Call" else "On call" }
        val dialing = !incoming && isDialing
        CallInfoProvider.update(IncomingCall(caller, incoming, answer, decline, dialing))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn?.notification
        val category = notification?.category
        val pkg = sbn?.packageName?.lowercase().orEmpty()
        val isCallPkg = pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("whatsapp") || pkg.contains("telecom") || pkg.contains("telegram")
        val isCall = category == Notification.CATEGORY_CALL || 
                     (android.os.Build.VERSION.SDK_INT >= 31 && notification?.extras?.getString(Notification.EXTRA_TEMPLATE)?.contains("CallStyle") == true) ||
                     isCallPkg
        if (isCall) {
            CallInfoProvider.update(null)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.d("NotificationListener", "onListenerConnected: Service is bound and listening!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        android.util.Log.d("NotificationListener", "onListenerDisconnected: Service disconnected")
    }

    private fun extractActions(
        notification: Notification,
        incoming: Boolean,
    ): Pair<PendingIntent?, PendingIntent?> {
        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        val actions = notification.actions
        actions?.forEach { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (title.contains("answer") || title.contains("accept")) ->
                    answer = action.actionIntent
                decline == null && (
                    title.contains("decline") || title.contains("reject") ||
                        title.contains("dismiss") || title.contains("hang") ||
                        title.contains("end") || title.contains("ignore")
                    ) -> decline = action.actionIntent
            }
        }
        if (incoming && answer == null && decline == null && actions?.size == 2) {
            decline = actions[0].actionIntent
            answer = actions[1].actionIntent
        }
        return answer to decline
    }

    private fun callerName(notification: Notification): String {
        val extras = notification.extras
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }
}
