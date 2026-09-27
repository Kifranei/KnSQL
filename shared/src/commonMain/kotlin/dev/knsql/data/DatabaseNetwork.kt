package dev.knsql.data

expect fun prepareDatabaseNetwork()

expect fun logFailure(tag: String, error: Throwable)
