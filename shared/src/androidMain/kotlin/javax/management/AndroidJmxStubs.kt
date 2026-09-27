package javax.management

interface NotificationListener {
    fun handleNotification(notification: Notification, handback: Any?)
}

class Notification {
    fun getType(): String = ""
}
