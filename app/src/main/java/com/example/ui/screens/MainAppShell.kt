package com.example.ui.screens

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.example.MainAppShellContent
import com.example.ui.AlarmViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(viewModel: AlarmViewModel) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val isPreview = LocalInspectionMode.current

    val localizedContext = remember(context, appLanguage) {
        if (isPreview) {
            context
        } else {
            val config = Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(appLanguage))
            context.createConfigurationContext(config)
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalActivityResultRegistryOwner provides (context as ComponentActivity)
    ) {
        MainAppShellContent(viewModel = viewModel)
    }
}
