package com.randomwaterreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri

object AppNavigation {
    const val PAGE_WATER = "water"
    const val PAGE_SCREEN = "screen"
    const val PAGE_WATER_CHECK_IN = "water-checkin"
    const val PAGE_WATER_HISTORY = "water-history"

    fun pageIntent(context: Context, page: String): Intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = baseUri(context, page).build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    fun waterCheckInIntent(
        context: Context,
        sessionId: String,
        isTest: Boolean = false,
        action: String = "prompt",
    ): Intent = pageIntent(context, PAGE_WATER_CHECK_IN).apply {
        data = baseUri(context, PAGE_WATER_CHECK_IN)
            .appendQueryParameter("sessionId", sessionId)
            .appendQueryParameter("isTest", isTest.toString())
            .appendQueryParameter("action", action)
            .build()
    }

    private fun baseUri(context: Context, page: String): Uri.Builder = Uri.Builder()
        .scheme(context.packageName)
        .authority("open")
        .appendPath(page)
}
