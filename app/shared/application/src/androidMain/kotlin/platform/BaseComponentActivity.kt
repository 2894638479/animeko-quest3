/*
 * Copyright (C) 2025 him188
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.him188.ani.app.platform

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Base Activity for Ani application components.
 *
 * Extends [BaseVRActivity] (Meta Spatial SDK) and manually provides
 * [androidx.lifecycle.LifecycleOwner], [ViewModelStoreOwner], [SavedStateRegistryOwner],
 * [OnBackPressedDispatcherOwner], and [ActivityResultCaller] — interfaces normally
 * provided by `androidx.activity.ComponentActivity` but not by the Meta SDK's
 * [com.meta.spatial.toolkit.AppSystemActivity].
 */
abstract class BaseComponentActivity : BaseVRActivity(),
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner,
    ActivityResultCaller,
    ActivityResultRegistryOwner {

    // ── Lifecycle (not provided by AppSystemActivity) ──────────────────────────

    private val _lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = _lifecycleRegistry

    // ── ViewModel / SavedState / Back (not provided by AppSystemActivity) ─────

    private val _viewModelStore = ViewModelStore()
    private val _savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _onBackPressedDispatcher = OnBackPressedDispatcher()
    private val _activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            val intent = contract.createIntent(this@BaseComponentActivity, input)
            this@BaseComponentActivity.startActivityForResult(intent, requestCode, options?.toBundle())
        }
    }

    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = _savedStateRegistryController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = _onBackPressedDispatcher
    override val activityResultRegistry: ActivityResultRegistry get() = _activityResultRegistry

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ViewModelProvider.AndroidViewModelFactory.getInstance(application)

    override val defaultViewModelCreationExtras: CreationExtras
        get() {
            val extras = MutableCreationExtras()
            extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application
            extras[VIEW_MODEL_STORE_OWNER_KEY] = this
            extras[object : CreationExtras.Key<SavedStateRegistryOwner> {}] = this
            intent?.extras?.let {
                extras[DEFAULT_ARGS_KEY] = it
            }
            return extras
        }

    override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> {
        return _activityResultRegistry.register("req_" + contract.hashCode(), this, contract, callback)
    }

    override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        registry: ActivityResultRegistry,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> {
        return registry.register("req_" + contract.hashCode(), this, contract, callback)
    }

    // ── Lifecycle-aware views ─────────────────────────────────────────────────

    override fun setContentView(view: View) {
        // Use the inherited lifecycle from AppSystemActivity — no duplicate LifecycleRegistry.
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        super.setContentView(view)
    }

    // ── Lifecycle callbacks ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        _savedStateRegistryController.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart() {
        super.onStart()
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        _lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        _viewModelStore.clear()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _savedStateRegistryController.performSave(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!_activityResultRegistry.dispatchResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ── Snackbar & Permissions ─────────────────────────────────────────────────

    val snackbarHostState = SnackbarHostState()

    private val requestPermissionHandlers: MutableCollection<(Boolean) -> Unit> = ConcurrentLinkedQueue()
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            requestPermissionHandlers.forEach { it.invoke(granted) }
        }
    private val requestPermissionLock = Mutex()

    private val requestExternalDocumentTreeHandler: AtomicRef<((Uri?) -> Unit)?> = atomic(null)
    private val requestExternalDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val handler by requestExternalDocumentTreeHandler
            handler?.invoke(uri)
        }

    suspend fun requestPermission(permission: String): Boolean {
        val res = CompletableDeferred<Boolean>()
        return requestPermissionLock.withLock {
            val handler: (Boolean) -> Unit = { res.complete(it) }
            requestPermissionHandlers.add(handler)
            try {
                requestPermissionLauncher.launch(permission)
                res.await()
            } finally {
                requestPermissionHandlers.remove(handler)
            }
        }
    }

    suspend fun requestExternalDocumentTree(): String? {
        val res = CompletableDeferred<String?>()
        val handler: (Uri?) -> Unit = { uri: Uri? -> res.complete(uri?.toString()) }
        if (!requestExternalDocumentTreeHandler.compareAndSet(null, handler)) return null
        return try {
            requestExternalDocumentTreeLauncher.launch(null)
            res.await()
        } finally {
            requestExternalDocumentTreeHandler.compareAndSet(handler, null)
        }
    }

    fun enableDrawingToSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

suspend fun BaseComponentActivity.showSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult = snackbarHostState.showSnackbar(message, actionLabel, withDismissAction, duration)

fun BaseComponentActivity.showSnackbarAsync(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
) {
    lifecycleScope.launch(Dispatchers.Main) {
        try {
            snackbarHostState.showSnackbar(message, actionLabel, withDismissAction, duration)
        } catch (e: Exception) { // exception will crash app
            e.printStackTrace()
        }
    }
}
