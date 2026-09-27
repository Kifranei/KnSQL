package dev.knsql.data

actual fun prepareDatabaseNetwork() = Unit

actual fun logFailure(tag: String, error: Throwable) {
    System.err.println(tag)
    error.printStackTrace()
}
