package org.mozilla.focus.activity;

import android.content.Context;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.ViewInteraction;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.text.format.DateUtils;

import junit.framework.Assert;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.focus.R;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.fastlane.screengrab.Screengrab;
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy;
import tools.fastlane.screengrab.locale.LocaleTestRule;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.Matchers.allOf;
import static org.mozilla.focus.activity.TestHelper.browserURLbar;
import static org.mozilla.focus.fragment.FirstrunFragment.FIRSTRUN_PREF;

@RunWith(AndroidJUnit4.class)
public class ScreenGrabTest {

    private MockWebServer webServer;
    private static final String TEST_PATH = "/";

    private enum ErrorTypes {
        ERROR_UNKNOWN (-1),
        ERROR_HOST_LOOKUP (-2),
        ERROR_CONNECT (-6),
        ERROR_TIMEOUT (-8),
        ERROR_REDIRECT_LOOP (-9),
        ERROR_UNSUPPORTED_SCHEME (-10),
        ERROR_FAILED_SSL_HANDSHAKE (-11),
        ERROR_BAD_URL (-12),
        ERROR_TOO_MANY_REQUESTS (-15);
        private int value;

        private ErrorTypes(int value) {
            this.value = value;
        }
    }

    @ClassRule
    public static final LocaleTestRule localeTestRule = new LocaleTestRule();

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule
            = new ActivityTestRule<MainActivity>(MainActivity.class) {

        @Override
        protected void beforeActivityLaunched() {
            super.beforeActivityLaunched();

            Context appContext = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getApplicationContext();
            Resources resources = appContext.getResources();

            PreferenceManager.getDefaultSharedPreferences(appContext)
                    .edit()
                    .putBoolean(FIRSTRUN_PREF, false)
                    .putBoolean(resources.getString(R.string.pref_key_secure), false)
                    .apply();

            webServer = new MockWebServer();

            try {
                webServer.enqueue(new MockResponse()
                        .setBody(TestHelper.readTestAsset("image_test.html"))
                        .addHeader("Set-Cookie", "sphere=battery; Expires=Wed, 21 Oct 2035 07:28:00 GMT;"));
                webServer.enqueue(new MockResponse()
                        .setBody(TestHelper.readTestAsset("rabbit.jpg")));

                webServer.start();
            } catch (IOException e) {
                throw new AssertionError("Could not start web server", e);
            }
        }

        @Override
        protected void afterActivityFinished() {
            super.afterActivityFinished();

            try {
                webServer.close();
                webServer.shutdown();
            } catch (IOException e) {
                throw new AssertionError("Could not stop web server", e);
            }
        }
    };

    public static void swipeDownNotificationBar (UiDevice deviceInstance) {
        int dHeight = deviceInstance.getDisplayHeight();
        int dWidth = deviceInstance.getDisplayWidth();
        int xScrollPosition = dWidth/2;
        int yScrollStop = dHeight/4 * 3;
        deviceInstance.swipe(
                xScrollPosition,
                yScrollStop,
                xScrollPosition,
                0,
                20
        );
    }

    @Test
    public void screenGrabTest() throws InterruptedException, UiObjectNotFoundException {
        UiDevice mDevice;
        final long waitingTime = DateUtils.SECOND_IN_MILLIS * 2;
        final String marketURL = "market://details?id=org.mozilla.firefox&referrer=utm_source%3D" +
                "mozilla%26utm_medium%3DReferral%26utm_campaign%3Dmozilla-org";

        // Initialize UiDevice instance
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Screengrab.setDefaultScreenshotStrategy(new UiAutomatorScreenshotStrategy());
        Screengrab.screenshot("IGNORE");

        /* Wait for app to load, and take the First View screenshot */
        UiObject firstSlide = mDevice.findObject(new UiSelector()
                .text("Browse like no one’s watching")
                .enabled(true));
        UiObject secondSlide = mDevice.findObject(new UiSelector()
                .text("Power up your privacy")
                .enabled(true));
        UiObject lastSlide = mDevice.findObject(new UiSelector()
                .text("A quick fix when\n" +
                        "blocking = breaking")
                .enabled(true));
        UiObject nextBtn = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/next")
                .enabled(true));
        UiObject finishBtn = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/finish")
                .enabled(true));
        firstSlide.waitForExists(waitingTime);
        Screengrab.screenshot("Onboarding_1_View");
        nextBtn.click();
        secondSlide.waitForExists(waitingTime);
        Screengrab.screenshot("Onboarding_2_View");
        nextBtn.click();
        lastSlide.waitForExists(waitingTime);
        Screengrab.screenshot("Onboarding_last_View");
        finishBtn.click();

        /* Home View*/
        UiObject urlBar = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/fake_urlbar")
                .clickable(true));
        urlBar.waitForExists(waitingTime);
        Screengrab.screenshot("Home_View");

        /* Main View Menu */
        ViewInteraction menuButton = onView(
                allOf(withId(R.id.menu),
                        isDisplayed()));
        menuButton.perform(click());

        UiObject RightsItem = mDevice.findObject(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(2));
        RightsItem.waitForExists(waitingTime);
        Screengrab.screenshot("MainViewMenu");

        /* Your Rights Page */
        RightsItem.click();
        UiObject webView = mDevice.findObject(new UiSelector()
        .className("android.webkit.Webview")
        .focused(true)
        .enabled(true));
        webView.waitForExists(waitingTime);
        Screengrab.screenshot("YourRights_Page");

        /* About Page */
        mDevice.pressBack();
        menuButton.perform(click());
        UiObject AboutItem = mDevice.findObject(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(0)
                .enabled(true));
        AboutItem.click();
        webView.waitForExists(waitingTime);
        Screengrab.screenshot("About_Page");

        /* Help Page */
        mDevice.pressBack();
        menuButton.perform(click());
        UiObject HelpItem = mDevice.findObject(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(1)
                .enabled(true));
        HelpItem.click();
        webView.waitForExists(waitingTime*5);
        Screengrab.screenshot("Help_Page");

        /* Location Bar View */
        mDevice.pressBack();
        urlBar.click();
        UiObject inlineAutocompleteEditText = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/url_edit")
                .focused(true)
                .enabled(true));
        inlineAutocompleteEditText.waitForExists(waitingTime);
        Screengrab.screenshot("LocationBarEmptyState");

        /* Autocomplete View */
        inlineAutocompleteEditText.clearTextField();
        inlineAutocompleteEditText.setText("mozilla");
        BySelector hint = By.clazz("android.widget.TextView")
                .res("org.mozilla.focus.debug","search_hint")
                .clickable(true);
        mDevice.wait(Until.hasObject(hint),waitingTime);
        Screengrab.screenshot("SearchFor");

        /* Browser View Menu */
        mDevice.pressKeyCode(KEYCODE_ENTER);
        webView.waitForExists(waitingTime);
        menuButton.perform(click());
        Screengrab.screenshot("BrowserViewMenu");

        /* Open_With View */
        UiObject openWithBtn = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/open_select_browser")
                .enabled(true));
        openWithBtn.waitForExists(waitingTime);
        openWithBtn.click();
        UiObject shareList = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/apps")
                .enabled(true));
        shareList.waitForExists(waitingTime);
        Screengrab.screenshot("OpenWith_Dialog");

        /* Share View */
        mDevice.pressBack();
        menuButton.perform(click());
        UiObject shareBtn = mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/share")
                .enabled(true));
        shareBtn.waitForExists(waitingTime);
        shareBtn.click();
        UiObject applist = mDevice.findObject(new UiSelector()
                .resourceId("android:id/resolver_list")
                .enabled(true));
        applist.waitForExists(waitingTime);
        Screengrab.screenshot("Share_Dialog");

        /* Notification bar caption */
        mDevice.openNotification();
        UiObject notificationBarDeleteItem = mDevice.findObject(new UiSelector()
                .text("Erase browsing history")
                .resourceId("android:id/text")
                .enabled(true));
        notificationBarDeleteItem.waitForExists(waitingTime);

        Screengrab.screenshot("DeleteHistory_NotificationBar");
        mDevice.pressBack();

        /* History Erase Notification */
        mDevice.pressBack();
        ViewInteraction floatingEraseButton = onView(
                allOf(withId(R.id.erase),
                        isDisplayed()));
        floatingEraseButton.perform(click());
        mDevice.wait(Until.findObject(By.res("org.mozilla.focus.debug","snackbar_text")), waitingTime);
        Screengrab.screenshot("YourBrowingHistoryHasBeenErased");

        /* Take Settings View */
        menuButton.perform(click());
        UiObject appItem = mDevice.findObject(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(3));
        appItem.click();
        BySelector settingsHeading = By.clazz("android.view.View")
                .res("org.mozilla.focus.debug","toolbar")
                .enabled(true);
        mDevice.wait(Until.hasObject(settingsHeading),waitingTime);
        Screengrab.screenshot("Settings_View_Top");

        /* Language List (First page only */
        UiObject settingsList = mDevice.findObject(new UiSelector()
                .resourceId("android:id/list").enabled(true));
        UiObject LanguageSelection = settingsList.getChild(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(0));
        LanguageSelection.click();
        mDevice.wait(Until.gone(settingsHeading),waitingTime);

        UiObject CancelBtn =  mDevice.findObject(new UiSelector()
                .resourceId("android:id/button2")
                .enabled(true));
        Screengrab.screenshot("Language_Selection");
        CancelBtn.click();
        mDevice.wait(Until.hasObject(settingsHeading),waitingTime);

        /* Search Engine List */
        UiObject SearchEngineSelection = settingsList.getChild(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(1));
        SearchEngineSelection.click();
        mDevice.wait(Until.gone(settingsHeading),waitingTime);
        UiObject SearchEngineList = new UiScrollable(new UiSelector()
                .resourceId("android:id/select_dialog_listview").enabled(true));
        UiObject FirstSelection = SearchEngineList.getChild(new UiSelector()
                .className("android.widget.LinearLayout")
                .instance(0));
        Screengrab.screenshot("SearchEngine_Selection");

        /* scroll down */
        FirstSelection.click();
        mDevice.wait(Until.hasObject(settingsHeading),waitingTime);
        UiScrollable settingsView = new UiScrollable(new UiSelector().scrollable(true));
        settingsView.scrollToEnd(3);
        Screengrab.screenshot("Settings_View_Bottom");

        // Go back
        mDevice.pressBack();
        urlBar.waitForExists(waitingTime);
        urlBar.click();

        /* Take image context menu screenshot */
        UiObject titleMsg = TestHelper.mDevice.findObject(new UiSelector()
                .description("focus test page")
                .enabled(true));

        UiObject rabbitImage = TestHelper.mDevice.findObject(new UiSelector()
                .description("Smiley face")
                .enabled(true));
        UiObject imageMenuTitle = TestHelper.mDevice.findObject(new UiSelector()
                .resourceId("org.mozilla.focus.debug:id/topPanel")
                .enabled(true));

        TestHelper.inlineAutocompleteEditText.waitForExists(waitingTime);
        TestHelper.inlineAutocompleteEditText.clearTextField();
        TestHelper.inlineAutocompleteEditText.setText(webServer.url(TEST_PATH).toString());
        TestHelper.pressEnterKey();
        TestHelper.webView.waitForExists(waitingTime);
        assertTrue("Website title loaded", titleMsg.exists());
        Assert.assertTrue(rabbitImage.exists());
        rabbitImage.dragTo(rabbitImage,7);
        imageMenuTitle.waitForExists(waitingTime);
        Assert.assertTrue(imageMenuTitle.exists());
        Screengrab.screenshot("Image_Context_Menu");
        TestHelper.mDevice.pressBack();
        browserURLbar.click();

        /* Go to google play market */
        inlineAutocompleteEditText.waitForExists(waitingTime);
        inlineAutocompleteEditText.setText(marketURL);
        mDevice.pressKeyCode(KEYCODE_ENTER);

        UiObject cancelBtn = mDevice.findObject(new UiSelector()
                .resourceId("android:id/button2"));

        cancelBtn.waitForExists(waitingTime);
        Screengrab.screenshot("Redirect_Outside");
        cancelBtn.click();
        UiObject tryAgainBtn = mDevice.findObject(new UiSelector()
                .resourceId("errorTryAgain")
                .clickable(true));
        mDevice.pressBack();
        urlBar.click();
        for (ScreenGrabTest.ErrorTypes error: ScreenGrabTest.ErrorTypes.values()) {

            inlineAutocompleteEditText.waitForExists(waitingTime);
            inlineAutocompleteEditText.setText("error:"+ error.value);
            mDevice.pressKeyCode(KEYCODE_ENTER);
            webView.waitForExists(waitingTime);
            tryAgainBtn.waitForExists(waitingTime);
            Screengrab.screenshot(error.name());
            browserURLbar.click();
        }
    }
}
