package com.kirikira.camdiss

import android.content.Context

enum class FlowStatus {
    IDLE,
    SEARCHING,
    WAITING_FOR_WIRELESS,
    CODE_REQUIRED,
    PAIRING,
    APPLYING,
    SUCCESS,
    ERROR,
}

data class FlowState(
    val status: FlowStatus,
    val detail: String = "",
)

object FlowStateStore {
    private const val PREFS = "camdiss_state"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val TRANSIENT_STATE_MAX_AGE_MS = 60_000L

    private val transientStatuses = setOf(
        FlowStatus.SEARCHING,
        FlowStatus.WAITING_FOR_WIRELESS,
        FlowStatus.CODE_REQUIRED,
        FlowStatus.PAIRING,
        FlowStatus.APPLYING,
    )

    fun read(context: Context): FlowState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = runCatching {
            FlowStatus.valueOf(prefs.getString(KEY_STATUS, FlowStatus.IDLE.name)!!)
        }.getOrDefault(FlowStatus.IDLE)
        val detail = prefs.getString(KEY_DETAIL, "").orEmpty()
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)

        // Versions <= 0.1.4 persisted in-progress states without a timestamp. After an
        // update/process death those states could survive forever and permanently disable
        // the Run button. Treat legacy or genuinely stale transient states as IDLE.
        if (status in transientStatuses) {
            val age = System.currentTimeMillis() - updatedAt
            if (updatedAt <= 0L || age < 0L || age > TRANSIENT_STATE_MAX_AGE_MS) {
                val idle = FlowState(FlowStatus.IDLE)
                writeInternal(context, idle)
                return idle
            }
        }

        return FlowState(status, detail)
    }

    fun write(context: Context, state: FlowState) {
        writeInternal(context, state)
        context.sendBroadcast(
            android.content.Intent(PairingService.ACTION_STATUS_CHANGED)
                .setPackage(context.packageName)
        )
    }

    private fun writeInternal(context: Context, state: FlowState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, state.status.name)
            .putString(KEY_DETAIL, state.detail)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }
}
