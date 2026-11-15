package com.lightnvr.companion

import android.app.Application
import com.lightnvr.companion.data.SessionStore
import com.lightnvr.companion.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the one session store + API client the whole app shares. */
class LightNvrApplication : Application() {
    lateinit var store: SessionStore
        private set
    lateinit var api: ApiClient
        private set

    /** For work that must finish even when its screen, tile or widget goes
     *  away (e.g. a mode change started from Quick Settings). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        api = ApiClient(store)
    }
}
