package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.Executors

object ReceiverWork {
    private const val TAG = "ReminderReceiver"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "water-reminder-receiver").apply { isDaemon = true }
    }

    fun run(receiver: BroadcastReceiver, label: String, block: () -> Unit) {
        val pending = receiver.goAsync()
        executor.execute {
            try {
                block()
            } catch (error: Throwable) {
                Log.e(TAG, "$label failed", error)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
