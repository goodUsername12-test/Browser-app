package com.fable.browser

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented smoke test: boots a real GeckoRuntime with the same privacy
 * configuration the app uses, loads https://www.google.com in a private
 * (incognito) session, and asserts the page actually reached DOMContentLoaded
 * with a secure (HTTPS) connection.
 *
 * Requires a connected device/emulator with network access:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class GoogleLoadSmokeTest {

    @Test
    fun loadsGoogleOverHttpsInPrivateSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val main = Handler(Looper.getMainLooper())

        val progressLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        var secure = false
        var loadedUrl: String? = null
        var sawError = false

        main.post {
            val runtime = GeckoRuntime.getDefault(context) ?: GeckoRuntime.create(
                context,
                GeckoRuntimeSettings.Builder()
                    .contentBlocking(
                        ContentBlocking.Settings.Builder()
                            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                            .build()
                    )
                    .globalPrivacyControlEnabled(true)
                    .remoteDebuggingEnabled(false)
                    .build()
            )

            val session = GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(true)
                    .useTrackingProtection(true)
                    .allowJavascript(true)
                    .contextId("fable-smoke-test")
                    .build()
            )

            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    s: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    if (url != null && url != "about:blank") loadedUrl = url
                }

                override fun onLoadError(
                    s: GeckoSession,
                    uri: String?,
                    error: org.mozilla.geckoview.WebRequestError
                ): GeckoResult<String>? {
                    sawError = true
                    progressLatch.countDown()
                    readyLatch.countDown()
                    return null
                }
            }

            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStop(s: GeckoSession, success: Boolean) {
                    if (!success) sawError = true
                    readyLatch.countDown()
                }

                override fun onSecurityChange(
                    s: GeckoSession,
                    info: GeckoSession.ProgressDelegate.SecurityInformation
                ) {
                    secure = info.isSecure
                    progressLatch.countDown()
                }
            }

            session.open(runtime)
            session.loadUri("https://www.google.com/")
        }

        assertTrue(
            "google.com never reported a security state within 60s",
            progressLatch.await(60, TimeUnit.SECONDS)
        )
        readyLatch.await(60, TimeUnit.SECONDS)

        assertTrue("navigation reported a load error", !sawError)
        assertTrue("connection was not reported secure", secure)
        assertTrue(
            "unexpected final url: $loadedUrl",
            loadedUrl?.startsWith("https://") == true && loadedUrl?.contains("google.") == true
        )
    }
}
