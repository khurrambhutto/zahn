package com.chotaai.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chotaai.app.AppContainer
import com.chotaai.app.container

/** `viewModel()` with access to the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel { create(container) }
}
