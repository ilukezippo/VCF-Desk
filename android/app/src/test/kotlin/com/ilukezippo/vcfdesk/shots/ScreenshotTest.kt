package com.ilukezippo.vcfdesk.shots

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ilukezippo.vcfdesk.MainActivity
import com.ilukezippo.vcfdesk.data.device.FakeContactsProvider
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders README/website screenshots with placeholder contacts ("Name N", 55555555-style numbers).
 * Skipped unless SHOTS_DIR is set:  SHOTS_DIR=/abs/dir ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val out = System.getenv("SHOTS_DIR")?.let(::File)

    @Before fun setUp() {
        assumeTrue(out != null)
        out!!.mkdirs()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS, Manifest.permission.READ_SYNC_SETTINGS,
        )
        val fake = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        // Sync on, as on a normal phone (otherwise the "sync is off" banner shows).
        android.content.ContentResolver.setMasterSyncAutomatically(true)
        android.content.ContentResolver.setSyncAutomatically(
            android.accounts.Account("name@gmail.com", "com.google"), ContactsContract.AUTHORITY, true,
        )
        val numbers = listOf("55555555", "99999999", "66666666", "55551111", "99992222", "66663333", "55554444",
            "99995555", "66667777")
        val accounts = listOf(
            "vnd.sec.contact.phone" to "vnd.sec.contact.phone", "com.google" to "name@gmail.com",
            "com.whatsapp" to "WhatsApp", "com.google" to "name@gmail.com",
        )
        numbers.forEachIndexed { i, num ->
            val n = i + 1
            val (type, acc) = accounts[i % accounts.size]
            val id = fake.addRaw(type, acc)
            fake.addRow(id, StructuredName.CONTENT_ITEM_TYPE, "data1" to "Name $n", "data2" to "Name $n")
            fake.addRow(id, Phone.CONTENT_ITEM_TYPE, "data1" to (if (i % 3 == 1) "+965 $num" else num), "data2" to 2)
            if (i % 2 == 0) fake.addRow(id, Email.CONTENT_ITEM_TYPE, "data1" to "name$n@example.com", "data2" to 1)
            if (i == 0) {
                fake.addRow(id, Phone.CONTENT_ITEM_TYPE, "data1" to "66666666", "data2" to 3)
                fake.addRow(id, Organization.CONTENT_ITEM_TYPE, "data1" to "Name Company", "data4" to "Name")
                fake.addRow(id, Event.CONTENT_ITEM_TYPE, "data1" to "1990-01-01", "data2" to 3)
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            v.draw(canvas)
            // Dialogs live in their own windows: dim the screen and draw each one centred on top.
            for (root in rootViews()) {
                if (root === v || root.width == 0) continue
                canvas.drawColor(0x99000000.toInt())
                canvas.save()
                canvas.translate((v.width - root.width) / 2f, (v.height - root.height) / 2f)
                root.draw(canvas)
                canvas.restore()
            }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<android.view.View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        return ArrayList(f.get(inst) as List<android.view.View>)
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also {
        scenario = it
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Name 1").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            shot("debug-launch"); throw e
        }
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun phone() {
        val s = launch()
        shot("android-list")
        compose.onAllNodesWithText("Name 1").onFirst().performClick()
        compose.waitForIdle()
        runCatching { compose.onAllNodesWithText("Name Company").onFirst().performScrollTo() }
        shot("android-editor")
        s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        for (n in listOf("Name 1", "Name 3", "Name 4", "Name 7")) compose.onAllNodesWithText(n).onFirst().performTouchInput { longClick() }
        shot("android-select")
        compose.onAllNodesWithText("Edit").onFirst().performClick()
        shot("android-bulk")
        compose.onAllNodesWithText("Add country code").onFirst().performClick()
        compose.onAllNodesWithText("Preview").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preview").fetchSemanticsNodes().size >= 2 }
        shot("android-preview")
    }

    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    @Test fun fold() {
        launch()
        compose.onAllNodesWithText("Name 1").onFirst().performClick()
        shot("android-fold")
    }
}
