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

    fun read(context: Context): FlowState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = runCatching {
            FlowStatus.valueOf(prefs.getString(KEY_STATUS, FlowStatus.IDLE.name)!!)
        }.getOrDefault(FlowStatus.IDLE)
        return FlowState(status, prefs.getString(KEY_DETAIL, "").orEmpty())
    }

    fun write(context: Context, state: FlowState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, state.status.name)
            .putString(KEY_DETAIL, state.detail)
            .apply()

        context.sendBroadcast(
            android.content.Intent(PairingService.ACTION_STATUS_CHANGED)
                .setPackage(context.packageName)
        )
    }
}
