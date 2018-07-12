/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.web

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.json.JSONException
import org.mozilla.focus.browser.LocalizedContent
import org.mozilla.focus.session.Session
import org.mozilla.focus.telemetry.SentryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.IntentUtils
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.UrlUtils
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.*
import org.mozilla.gecko.util.ThreadUtils
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * WebViewProvider implementation for creating a Gecko based implementation of IWebView.
 */
object WebViewProvider {
    @Volatile
    private var geckoRuntime: GeckoRuntime? = null

    fun preload(context: Context) {
        createGeckoRuntimeIfNeeded(context)
    }

    fun create(context: Context, attrs: AttributeSet): View {
        return GeckoWebView(context, attrs)
    }

    fun performCleanup(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Nothing: does Gecko need extra private mode cleanup?
    }

    fun performNewBrowserSessionCleanup() {
        // Nothing: a WebKit work-around.
    }

    private fun createGeckoRuntimeIfNeeded(context: Context) {
        if (geckoRuntime == null) {
            val runtimeSettingsBuilder = GeckoRuntimeSettings.Builder()
            runtimeSettingsBuilder.useContentProcessHint(true)
            runtimeSettingsBuilder.nativeCrashReportingEnabled(true)
            geckoRuntime = GeckoRuntime.create(context.applicationContext, runtimeSettingsBuilder.build())
        }
    }

    class GeckoWebView(context: Context, attrs: AttributeSet) : NestedGeckoView(context, attrs), IWebView, SharedPreferences.OnSharedPreferenceChangeListener {
        private var callback: IWebView.Callback? = null
        private var currentUrl: String = "about:blank"
        private var canGoBack: Boolean = false
        private var canGoForward: Boolean = false
        private var isSecure: Boolean = false
        private var geckoSession: GeckoSession? = null
        private var webViewTitle: String? = null
        private var isLoadingInternalUrl = false
        private var internalAboutData: String? = null
        private var internalRightsData: String? = null

        init {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .registerOnSharedPreferenceChangeListener(this)
            geckoSession = createGeckoSession()
            applySettingsAndSetDelegates()
            setSession(geckoSession!!, geckoRuntime)
        }

        private fun applySettingsAndSetDelegates() {
            applyAppSettings()
            updateBlocking()

            geckoSession!!.contentDelegate = createContentDelegate()
            geckoSession!!.progressDelegate = createProgressDelegate()
            geckoSession!!.navigationDelegate = createNavigationDelegate()
            geckoSession!!.trackingProtectionDelegate = createTrackingProtectionDelegate()
            geckoSession!!.promptDelegate = createPromptDelegate()
        }

        private fun createGeckoSession(): GeckoSession {
            val settings = GeckoSessionSettings()
            settings.setBoolean(GeckoSessionSettings.USE_MULTIPROCESS, true)
            settings.setBoolean(GeckoSessionSettings.USE_PRIVATE_MODE, true)

            return GeckoSession(settings)
        }

        override fun setCallback(callback: IWebView.Callback?) {
            this.callback = callback
        }

        override fun onPause() {

        }

        override fun goBack() {
            geckoSession!!.goBack()
        }

        override fun goForward() {
            geckoSession!!.goForward()
        }

        override fun reload() {
            geckoSession!!.reload()
        }

        override fun destroy() {
            geckoSession!!.close()
        }

        override fun onResume() {
            if (TelemetryWrapper.dayPassedSinceLastUpload(getContext())) {
                sendTelemetrySnapshots()
            }
        }

        override fun stopLoading() {
            geckoSession!!.stop()
            callback?.onPageFinished(isSecure)
        }

        override fun getUrl(): String? {
            return currentUrl
        }

        override fun loadUrl(url: String) {
            currentUrl = url
            geckoSession!!.loadUri(currentUrl)
            callback?.onProgress(10)
        }

        override fun cleanup() {
            // We're running in a private browsing window, so nothing to do
        }

        override fun setBlockingEnabled(enabled: Boolean) {
            geckoSession!!.settings.setBoolean(GeckoSessionSettings.USE_TRACKING_PROTECTION, enabled)
            if (enabled) {
                updateBlocking()
                applyAppSettings()
            } else {
                geckoRuntime?.settings?.javaScriptEnabled = true
                geckoRuntime?.settings?.webFontsEnabled = true
            }
            callback?.onBlockingStateChanged(enabled)
        }

        override fun setRequestDesktop(shouldRequestDesktop: Boolean) {
            geckoSession!!.settings.setBoolean(GeckoSessionSettings.USE_DESKTOP_MODE, shouldRequestDesktop)
            callback?.onRequestDesktopStateChanged(shouldRequestDesktop)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, prefName: String) {
            updateBlocking()
            applyAppSettings()
        }

        private fun applyAppSettings() {
            geckoRuntime!!.settings.javaScriptEnabled = !Settings.getInstance(getContext()).shouldBlockJavaScript()
            geckoRuntime!!.settings.webFontsEnabled = !Settings.getInstance(getContext()).shouldBlockWebFonts()
            geckoRuntime!!.settings.remoteDebuggingEnabled = false
            val cookiesValue: Int
            if (Settings.getInstance(context).shouldBlockCookies() && Settings.getInstance(getContext()).shouldBlockThirdPartyCookies()) {
                cookiesValue = GeckoRuntimeSettings.COOKIE_ACCEPT_NONE
            } else if (Settings.getInstance(context).shouldBlockThirdPartyCookies()) {
                cookiesValue = GeckoRuntimeSettings.COOKIE_ACCEPT_FIRST_PARTY
            } else {
                cookiesValue = GeckoRuntimeSettings.COOKIE_ACCEPT_ALL
            }
            geckoRuntime!!.settings.cookieBehavior = cookiesValue
        }

        private fun updateBlocking() {
            val settings = Settings.getInstance(context)

            var categories = 0
            if (settings.shouldBlockSocialTrackers()) {
                categories += GeckoSession.TrackingProtectionDelegate.CATEGORY_SOCIAL
            }
            if (settings.shouldBlockAdTrackers()) {
                categories += GeckoSession.TrackingProtectionDelegate.CATEGORY_AD
            }
            if (settings.shouldBlockAnalyticTrackers()) {
                categories += GeckoSession.TrackingProtectionDelegate.CATEGORY_ANALYTIC
            }
            if (settings.shouldBlockOtherTrackers()) {
                categories += GeckoSession.TrackingProtectionDelegate.CATEGORY_CONTENT
            }

            geckoRuntime!!.settings.trackingProtectionCategories = categories
        }

        private fun createContentDelegate(): GeckoSession.ContentDelegate {
            return object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String) {
                    webViewTitle = title
                }

                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    if (fullScreen) {
                        callback?.onEnterFullScreen({ geckoSession!!.exitFullScreen() }, null)
                    } else {
                        callback?.onExitFullScreen()
                    }
                }

                override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, uri: String?, @GeckoSession.ContentDelegate.ElementType elementType: Int, elementSrc: String?) {
                    if (elementSrc != null && uri != null && elementType == GeckoSession.ContentDelegate.ELEMENT_TYPE_IMAGE) {
                        callback?.onLongPress(IWebView.HitTarget(true, uri, true, elementSrc))
                    } else if (elementSrc != null && elementType == GeckoSession.ContentDelegate.ELEMENT_TYPE_IMAGE) {
                        callback?.onLongPress(IWebView.HitTarget(false, null, true, elementSrc))
                    } else if (uri != null) {
                        callback?.onLongPress(IWebView.HitTarget(true, uri, false, null))
                    }
                }

                override fun onExternalResponse(session: GeckoSession, response: GeckoSession.WebResponseInfo) {
                    if (!AppConstants.supportsDownloadingFiles()) {
                        return
                    }

                    val scheme = Uri.parse(response.uri).scheme
                    if (scheme == null || scheme != "http" && scheme != "https") {
                        // We are ignoring everything that is not http or https. This is a limitation of
                        // Android's download manager. There's no reason to show a download dialog for
                        // something we can't download anyways.
                        Log.w(TAG, "Ignoring download from non http(s) URL: " + response.uri)
                        return
                    }

                    // TODO: get user agent from GeckoView #2470
                    val download = Download(response.uri, "Mozilla/5.0 (Android 8.1.0; Mobile; rv:60.0) Gecko/60.0 Firefox/60.0",
                            response.filename, response.contentType, response.contentLength,
                            Environment.DIRECTORY_DOWNLOADS)
                    callback?.onDownloadStart(download)
                }

                override fun onCrash(session: GeckoSession) {
                    Log.i(TAG, "Crashed, opening new session")
                    SentryWrapper.captureGeckoCrash()
                    geckoSession!!.close()
                    geckoSession = createGeckoSession()
                    applySettingsAndSetDelegates()
                    geckoSession!!.open(geckoRuntime!!)
                    setSession(geckoSession!!)
                    geckoSession!!.loadUri(currentUrl)
                }

                override fun onFocusRequest(geckoSession: GeckoSession) {

                }

                override fun onCloseRequest(geckoSession: GeckoSession) {
                    // TODO: #2150
                }
            }
        }

        private fun createProgressDelegate(): GeckoSession.ProgressDelegate {
            return object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    callback?.onPageStarted(url)
                    callback?.resetBlockedTrackers()
                    callback?.onProgress(25)
                    isSecure = false
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (success) {
                        if (UrlUtils.isLocalizedContent(url)) {
                            // When the url is a localized content, then the page is secure
                            isSecure = true
                        }

                        callback?.onProgress(100)
                        callback?.onPageFinished(isSecure)

                    }
                }

                override fun onSecurityChange(session: GeckoSession,
                                              securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
                    isSecure = securityInfo.isSecure

                    if (UrlUtils.isLocalizedContent(url)) {
                        // When the url is a localized content, then the page is secure
                        isSecure = true
                    }

                    callback?.onSecurityChanged(isSecure, securityInfo.host, securityInfo.issuerOrganization)
                }
            }
        }

        private fun createNavigationDelegate(): GeckoSession.NavigationDelegate {
            return object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(session: GeckoSession, url: String) {
                    var newURL = url
                    // Save internal data: urls we should override to present focus:about, focus:rights
                    if (isLoadingInternalUrl) {
                        if (currentUrl == LocalizedContent.URL_ABOUT) {
                            internalAboutData = newURL
                        } else if (currentUrl == LocalizedContent.URL_RIGHTS) {
                            internalRightsData = newURL
                        }
                        isLoadingInternalUrl = false
                        newURL = currentUrl
                    }

                    // Check for internal data: urls to instead present focus:rights, focus:about
                    if (!TextUtils.isEmpty(internalAboutData) && internalAboutData == newURL) {
                        newURL = LocalizedContent.URL_ABOUT
                    } else if (!TextUtils.isEmpty(internalRightsData) && internalRightsData == newURL) {
                        newURL = LocalizedContent.URL_RIGHTS
                    }

                    currentUrl = newURL
                    callback?.onURLChanged(newURL)
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    this@GeckoWebView.canGoBack = canGoBack
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    this@GeckoWebView.canGoForward = canGoForward
                }

                override fun onLoadRequest(session: GeckoSession, uri: String, target: Int, flags: Int, response: GeckoResponse<Boolean>) {
                    // If this is trying to load in a new tab, just load it in the current one
                    if (target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                        geckoSession!!.loadUri(uri)
                        response.respond(true)
                    }

                    // Check if we should handle an internal link
                    if (LocalizedContent.handleInternalContent(uri, this@GeckoWebView, context)) {
                        response.respond(true)
                    }

                    // Check if we should handle an external link
                    val urlToURI = Uri.parse(uri)
                    if (!UrlUtils.isSupportedProtocol(urlToURI.scheme) && callback != null &&
                            IntentUtils.handleExternalUri(context, this@GeckoWebView, uri)) {
                        response.respond(true)
                    }

                    if (uri == "about:neterror" || uri == "about:certerror") {
                        // TODO: Error Page handling with Components ErrorPages #2471
                        response.respond(true)
                    }

                    callback?.onRequest(flags == GeckoSession.NavigationDelegate.LOAD_REQUEST_IS_USER_TRIGGERED)

                    // Otherwise allow the load to continue normally
                    response.respond(false)
                }

                override fun onNewSession(session: GeckoSession, uri: String, response: GeckoResponse<GeckoSession>) {
                    // TODO: #2151
                }
            }
        }

        private fun createTrackingProtectionDelegate(): GeckoSession.TrackingProtectionDelegate {
            return GeckoSession.TrackingProtectionDelegate { _, _, _ ->
                callback?.countBlockedTracker()
            }
        }

        private fun createPromptDelegate(): GeckoSession.PromptDelegate {
            return GeckoViewPrompt(context as Activity)
        }

        override fun canGoForward(): Boolean {
            return canGoForward
        }

        override fun canGoBack(): Boolean {
            return canGoBack
        }

        override fun restoreWebViewState(session: Session) {
            val stateData = session.webViewState
            val desiredURL = session.url.value
            val sessionState = stateData.getParcelable<GeckoSession.SessionState>("state")
            if (sessionState != null) {
                geckoSession!!.restoreState(sessionState)
            } else {
                loadUrl(desiredURL)
            }
        }

        override fun saveWebViewState(session: Session) {
            val latch = CountDownLatch(1)
            saveStateInBackground(latch, session)
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                // State was not saved
            }

        }

        private fun saveStateInBackground(latch: CountDownLatch, session: Session) {
            ThreadUtils.postToBackgroundThread {
                val response = GeckoResponse<GeckoSession.SessionState> { value ->
                    if (value != null) {
                        val bundle = Bundle()
                        bundle.putParcelable("state", value)
                        session.saveWebViewState(bundle)
                    }
                    latch.countDown()
                }

                geckoSession?.saveState(response)
            }
        }

        override fun getTitle(): String? {
            return webViewTitle
        }

        override fun exitFullscreen() {
            geckoSession!!.exitFullScreen()
        }

        override fun findAllAsync(find: String) {
            // TODO: #2690
        }

        override fun findNext(forward: Boolean) {
            // TODO: #2690
        }

        override fun clearMatches() {
            // TODO: #2690
        }

        override fun setFindListener(findListener: IFindListener) {
            // TODO: #2690
        }

        override fun loadData(baseURL: String, data: String, mimeType: String, encoding: String, historyURL: String) {
            geckoSession!!.loadData(data.toByteArray(Charsets.UTF_8), mimeType, baseURL)
            currentUrl = baseURL
            isLoadingInternalUrl = currentUrl == LocalizedContent.URL_RIGHTS || currentUrl == LocalizedContent.URL_ABOUT
        }

        private fun sendTelemetrySnapshots() {
            val response = GeckoResponse<GeckoBundle> { value ->
                if (value != null) {
                    try {
                        val jsonData = value.toJSONObject()
                        TelemetryWrapper.addMobileMetricsPing(jsonData)
                    } catch (e: JSONException) {
                        Log.e("getSnapshots failed", e.message)
                    }
                }
            }

            geckoRuntime!!.telemetry.getSnapshots(true, response)
        }

        override fun onDetachedFromWindow() {
            PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(this)
            super.onDetachedFromWindow()
        }

        companion object {
            private const val TAG = "GeckoWebView"
        }
    }
}
