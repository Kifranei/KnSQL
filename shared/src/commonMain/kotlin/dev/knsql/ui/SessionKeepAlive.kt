package dev.knsql.ui

import androidx.compose.runtime.Composable

/**
 * Keeps the process alive while sessions are open. On Android this runs a foreground service with
 * an ongoing notification and asks for the notification permission the first time it is needed.
 */
@Composable
expect fun SessionKeepAlive(sessionNames: List<String>, enabled: Boolean)
