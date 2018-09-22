/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget

import android.content.Context
import android.support.v7.preference.Preference
import android.util.AttributeSet
import org.mozilla.focus.utils.Settings

class CookiesSettingsPreference(context: Context?, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        updateSummary()
    }

    private fun updateSummary() {
        val settings = Settings.getInstance(context)
        summary = settings.shouldBlockCookiesValue()
    }
}
