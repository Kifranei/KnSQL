package dev.knsql.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.knsql.data.SessionKeepAliveService
import dev.knsql.data.logFailure

@Composable
actual fun SessionKeepAlive(sessionNames: List<String>, enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val active = enabled && sessionNames.isNotEmpty()
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(active) {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (active && !granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(active, sessionNames) {
        val intent = Intent(context, SessionKeepAliveService::class.java)
        if (active) {
            intent.putStringArrayListExtra(SessionKeepAliveService.EXTRA_NAMES, ArrayList(sessionNames))
            runCatching { context.startForegroundService(intent) }
                .onFailure { logFailure("keepalive", it) }
        } else {
            context.stopService(intent)
        }
    }
}
