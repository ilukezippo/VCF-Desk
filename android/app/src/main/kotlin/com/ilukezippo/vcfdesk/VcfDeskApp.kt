package com.ilukezippo.vcfdesk

import android.app.Application
import android.content.Context
import com.ilukezippo.vcfdesk.data.BulkRunner
import com.ilukezippo.vcfdesk.data.ContactSource
import com.ilukezippo.vcfdesk.data.Settings
import com.ilukezippo.vcfdesk.data.SourceId
import com.ilukezippo.vcfdesk.data.backup.BackupStore
import com.ilukezippo.vcfdesk.data.device.DeviceSource
import com.ilukezippo.vcfdesk.data.google.GoogleAuth
import com.ilukezippo.vcfdesk.data.google.GoogleSource
import com.ilukezippo.vcfdesk.data.google.PeopleApi
import java.io.File
import okhttp3.OkHttpClient

/** Hand-wired app-level objects; no DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val settings = Settings(app)
    val deviceSource: DeviceSource by lazy { DeviceSource(app) }
    val backupStore = BackupStore(File(app.filesDir, "backups"))
    val bulkRunner = BulkRunner(backupStore)
    private val http by lazy { OkHttpClient() }

    /** Set by the running activity (the consent screens need one); null while there is none. */
    @Volatile var googleAuth: GoogleAuth? = null

    private val googleSources = HashMap<String, GoogleSource>()

    @Synchronized
    fun googleSource(email: String): GoogleSource = googleSources.getOrPut(email) {
        GoogleSource(email, PeopleApi(http, token = {
            val auth = googleAuth ?: throw java.io.IOException("Google sign-in is not available right now")
            auth.token(email)
        }, onUnauthorized = { refused -> googleAuth?.clearToken(refused) }))
    }

    fun source(id: SourceId): ContactSource = when (id) {
        SourceId.Device -> deviceSource
        is SourceId.Google -> googleSource(id.email)
    }
}

class VcfDeskApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
