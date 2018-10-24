/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.support.v7.preference.ListPreference
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import org.mozilla.focus.R
import org.mozilla.focus.locale.LocaleManager
import org.mozilla.focus.locale.Locales
import java.nio.ByteBuffer
import java.text.Collator
import java.util.Arrays
import java.util.Locale

class LocaleListPreference(context: Context?, attrs: AttributeSet?) : ListPreference(
    context,
    attrs
) {
    @Volatile
    private var entriesLocale: Locale? = null

    // Thus far, missing glyphs are replaced by whitespace, not a box
    // or other Unicode codepoint.
    private val characterValidator: CharacterValidator = CharacterValidator(" ")

    private val selectedLocale: Locale
        get() {
            val tag = value
            return if (tag == null || tag == "") {
                Locale.getDefault()
            } else Locales.parseLocaleCode(tag)
        }

    override fun onAttached() {
        super.onAttached()

        initializeLocaleList()
    }

    private fun initializeLocaleList() {
        val currentLocale = Locale.getDefault()

        if (currentLocale == this.entriesLocale && entries != null) {
            Log.v(LOG_TAG, "No need to build list.")
            return
        }

        this.entriesLocale = currentLocale

        val defaultLanguage = context.getString(R.string.preference_language_systemdefault)

        launch {
            buildLocaleFunc(defaultLanguage).also {
                entries = it.first
                entryValues = it.second
            }
        }
    }

    /**
     * Not every locale we ship can be used on every device, due to
     * font or rendering constraints.
     *
     *
     * This method filters down the list before generating the descriptor array.
     */
    private val usableLocales: Array<LocaleDescriptor>
        get() {
            val initialCount = LocaleManager.getPackagedLocaleTags(context).size
            val locales = HashSet<LocaleDescriptor>(initialCount)
            for (tag in LocaleManager.getPackagedLocaleTags(context)) {
                val descriptor = LocaleDescriptor(tag)
                if (!descriptor.isUsable(characterValidator)) {
                    Log.v(LOG_TAG, "Skipping locale $descriptor")
                } else {
                    locales.add(descriptor)
                }
            }
            val usableCount = locales.size
            val descriptors = locales.toTypedArray()
            Arrays.sort(descriptors, 0, usableCount)
            return descriptors
        }

    private suspend fun buildLocaleFunc(systemDefaultLanguage: String): Pair<Array<String>, Array<String>> {
        val descriptors = usableLocales
        val count = descriptors.size

        // We leave room for "System default".
        val entries = Array(
            count + 1
        ) { i ->
            withContext(DefaultDispatcher) {
                getEntriesAsync(
                    i,
                    descriptors,
                    systemDefaultLanguage
                )
            }
        }

        val values = Array(count + 1) { i ->
            withContext(DefaultDispatcher) { getValuesAysnc(i, descriptors) }
        }

        return Pair(entries, values)
    }

    private fun getEntriesAsync(
        i: Int,
        descriptors: Array<LocaleDescriptor>,
        systemDefaultLanguage: String
    ): String {
        return if (i == 0) systemDefaultLanguage else descriptors[i - 1].displayName
    }

    private fun getValuesAysnc(i: Int, descriptors: Array<LocaleDescriptor>): String {
        return if (i == 0) "" else descriptors[i - 1].tag
    }

    private class LocaleDescriptor(locale: Locale, val tag: String) : Comparable<LocaleDescriptor> {
        var displayName: String

        constructor(tag: String) : this(Locales.parseLocaleCode(tag), tag)

        init {
            var displayName: String? = when {
                languageCodeToNameMap.containsKey(locale.language) -> languageCodeToNameMap[locale.language]
                localeToNameMap.containsKey(locale.toLanguageTag()) -> localeToNameMap[locale.toLanguageTag()]
                else -> locale.getDisplayName(locale)
            }

            if (TextUtils.isEmpty(displayName)) {
                // There's nothing sane we can do.
                Log.w(LOG_TAG, "Display name is empty. Using " + locale.toString())
                displayName = locale.toString()
            } else if (Character.getDirectionality(displayName!![0]) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                // For now, uppercase the first character of LTR locale names.
                // This is pretty much what Android does. This is a reasonable hack
                // for Bug 1014602, but it won't generalize to all locales.
                var firstLetter = displayName.substring(0, 1)

                // Android OS creates an instance of Transliterator to convert the first letter
                // of the Greek locale. See CaseMapper.toUpperCase(Locale locale, String s, int count)
                // Since it's already in upper case, we don't need it
                if (!Character.isUpperCase(firstLetter[0])) {
                    firstLetter = firstLetter.toUpperCase(locale)
                }
                displayName = firstLetter + displayName.substring(1)
            }

            this.displayName = displayName
        }

        override fun toString(): String {
            return this.displayName
        }

        override fun equals(other: Any?): Boolean {
            return other is LocaleDescriptor && compareTo((other as LocaleDescriptor?)!!) == 0
        }

        override fun hashCode(): Int {
            return tag.hashCode()
        }

        override fun compareTo(other: LocaleDescriptor): Int {
            // We sort by name, so we use Collator.
            return COLLATOR.compare(this.displayName, other.displayName)
        }

        /**
         * See Bug 1023451 Comment 10 for the research that led to
         * this method.
         *
         * @return true if this locale can be used for displaying UI
         * on this device without known issues.
         */
        fun isUsable(validator: CharacterValidator): Boolean {
            // Oh, for Java 7 switch statements.
            if (this.tag == "bn-IN") {
                // Bengali sometimes has an English label if the Bengali script
                // is missing. This prevents us from simply checking character
                // rendering for bn-IN; we'll get a false positive for "B", not "ব".
                //
                // This doesn't seem to affect other Bengali-script locales
                // (below), which always have a label in native script.
                if (!this.displayName.startsWith("বাংলা")) {
                    // We're on an Android version that doesn't even have
                    // characters to say বাংলা. Definite failure.
                    return false
                }
            }

            // These locales use a script that is often unavailable
            // on common Android devices. Make sure we can show them.
            // See documentation for CharacterValidator.
            // Note that bn-IN is checked here even if it passed above.
            return this.tag != "or" &&
                    this.tag != "my" &&
                    this.tag != "pa-IN" &&
                    this.tag != "gu-IN" &&
                    this.tag != "bn-IN" || !validator.characterIsMissingInFont(
                this.displayName.substring(
                    0,
                    1
                )
            )
        }

        companion object {
            // We use Locale.US here to ensure a stable ordering of entries.
            private val COLLATOR = Collator.getInstance(Locale.US)
        }
    }

    override fun onClick() {
        super.onClick()

        // Use this hook to try to fix up the environment ASAP.
        // Do this so that the redisplayed fragment is inflated
        // with the right locale.
        val selectedLocale = selectedLocale
        val context = context
        LocaleManager.getInstance().updateConfiguration(context, selectedLocale)
    }

    override fun getSummary(): CharSequence {
        val value = value

        // We can't trust super.getSummary() across locale changes,
        // apparently, so let's do the same work.
        return if (TextUtils.isEmpty(value)) {
            context.getString(R.string.preference_language_systemdefault)
        } else LocaleDescriptor(value).displayName
    }

    /**
     * With thanks to <http:></http:>//stackoverflow.com/a/22679283/22003> for the
     * initial solution.
     *
     *
     * This class encapsulates an approach to checking whether a script
     * is usable on a device. We attempt to draw a character from the
     * script (e.g., ব). If the fonts on the device don't have the correct
     * glyph, Android typically renders whitespace (rather than .notdef).
     *
     *
     * Pass in part of the name of the locale in its local representation,
     * and a whitespace character; this class performs the graphical comparison.
     *
     * Note: this constructor fails when running in Robolectric: robolectric only supports bitmaps
     * with 4 bytes per pixel
     * https://github.com/robolectric/robolectric/blob/master/robolectric-shadows/shadows-core/
     * src/main/java/org/robolectric/shadows/ShadowBitmap.java#L540
     * We need to either make this code test-aware, or fix robolectric.
     *
     *
     * See Bug 1023451 Comment 24 for extensive explanation.
     */
    class CharacterValidator(missing: String) {

        private val paint = Paint()
        private val missingCharacter: ByteArray

        init {
            this.missingCharacter = getPixels(drawBitmap(missing))
        }

        private fun drawBitmap(text: String): Bitmap {
            val b = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ALPHA_8)
            val c = Canvas(b)
            c.drawText(text, 0f, (BITMAP_HEIGHT / 2).toFloat(), this.paint)
            return b
        }

        fun characterIsMissingInFont(ch: String): Boolean {
            val rendered = getPixels(drawBitmap(ch))
            return Arrays.equals(rendered, missingCharacter)
        }

        companion object {
            private val BITMAP_WIDTH = 32
            private val BITMAP_HEIGHT = 48

            private fun getPixels(b: Bitmap): ByteArray {
                val byteCount = b.allocationByteCount

                val buffer = ByteBuffer.allocate(byteCount)
                b.copyPixelsToBuffer(buffer)

                return buffer.array()
            }
        }
    }

    companion object {
        private val LOG_TAG = "GeckoLocaleList"

        private val languageCodeToNameMap = HashMap<String, String>()
        private val localeToNameMap = HashMap<String, String>()

        init {
            // Only ICU 57 actually contains the Asturian name for Asturian, even Android 7.1 is still
            // shipping with ICU 56, so we need to override the Asturian name (otherwise displayName will
            // be the current locales version of Asturian, see:
            // https://github.com/mozilla-mobile/focus-android/issues/634#issuecomment-303886118
            languageCodeToNameMap["ast"] = "Asturianu"
            // On an Android 8.0 device those languages are not known and we need to add the names
            // manually. Loading the resources at runtime works without problems though.
            languageCodeToNameMap["cak"] = "Kaqchikel"
            languageCodeToNameMap["ia"] = "Interlingua"
            languageCodeToNameMap["meh"] = "Tu´un savi ñuu Yasi'í Yuku Iti"
            languageCodeToNameMap["mix"] = "Tu'un savi"
            languageCodeToNameMap["trs"] = "Triqui"
            languageCodeToNameMap["zam"] = "DíɁztè"
            languageCodeToNameMap["oc"] = "occitan"
            languageCodeToNameMap["an"] = "Aragonés"
            languageCodeToNameMap["tt"] = "татарча"
            languageCodeToNameMap["wo"] = "Wolof"
            languageCodeToNameMap["anp"] = "अंगिका"
            languageCodeToNameMap["ixl"] = "Ixil"
            languageCodeToNameMap["pai"] = "Paa ipai"
            languageCodeToNameMap["quy"] = "Chanka Qhichwa"
            languageCodeToNameMap["ay"] = "Aimara"
            languageCodeToNameMap["quc"] = "K'iche'"
            languageCodeToNameMap["tsz"] = "P'urhepecha"
            languageCodeToNameMap["jv"] = "Basa Jawa"
            languageCodeToNameMap["ppl"] = "Náhuat Pipil"
            languageCodeToNameMap["su"] = "Basa Sunda"
        }

        init {
            // Override the native name for certain locale regions based on language community needs.
            localeToNameMap["zh-CN"] = "中文 (中国大陆)"
        }
    }
}
