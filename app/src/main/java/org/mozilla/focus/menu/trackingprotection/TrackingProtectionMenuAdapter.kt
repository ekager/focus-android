/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.menu.trackingprotection

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import org.mozilla.focus.R
import org.mozilla.focus.fragment.BrowserFragment
import org.mozilla.focus.utils.Browsers
import java.lang.ref.WeakReference

class TrackingProtectionMenuAdapter(
    private val context: Context,
    private val menu: TrackingProtectionMenu,
    private val fragment: BrowserFragment
) : RecyclerView.Adapter<TrackingProtectionMenuViewHolder>() {
    sealed class MenuItem {
        open val viewType = 0

        object BlockingSwitch : MenuItem() {
            override val viewType = BlockingItemViewHolder.LAYOUT_ID
        }
    }

    private var items = mutableListOf<MenuItem>()
    private var blockingItemViewHolderReference = WeakReference<BlockingItemViewHolder>(null)

    init {
        initializeMenu(fragment.url)
    }

    private fun initializeMenu(url: String) {
        val resources = context.resources
        val browsers = Browsers(context, url)

        items.add(MenuItem.BlockingSwitch)
    }

    fun updateTrackers(trackers: Int) {
        val navigationItemViewHolder = blockingItemViewHolderReference.get() ?: return
        navigationItemViewHolder.updateTrackers(trackers)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): TrackingProtectionMenuViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            BlockingItemViewHolder.LAYOUT_ID -> {
                val blockingItemViewHolder =
                    BlockingItemViewHolder(
                        inflater.inflate(R.layout.menu_blocking_switch, parent, false), fragment
                    )
                blockingItemViewHolderReference = WeakReference(blockingItemViewHolder)
                blockingItemViewHolder
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: TrackingProtectionMenuViewHolder, position: Int) {
        holder.menu = menu
        holder.setOnClickListener(fragment)

        val item = items[position]
        when (item) {
            is MenuItem.Custom -> (holder as CustomTabMenuItemViewHolder).bind(item)
            is MenuItem.Default -> (holder as MenuItemViewHolder).bind(item)
        }
    }

    override fun getItemViewType(position: Int): Int = items[position].viewType
    override fun getItemCount(): Int = items.size
}
