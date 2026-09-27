package dev.knsql.ui

import androidx.compose.runtime.Composable

@Composable
expect fun AboutBackHandler(enabled: Boolean, onBack: () -> Unit)
