package com.example.ringman

import android.content.Context
import android.content.SharedPreferences

enum class ServiceState {
    ENABLED,
    DISABLED,
}

private const val name = "SERVICE_STATE"
private const val key = "SERVICE_STATE_KEY"

private fun getPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(name, 0)
}

fun saveServiceState(context: Context, state: ServiceState) {
    with(getPreferences(context).edit()) {
        putString(key, state.name)
        apply()
    }
}

fun getServiceState(context: Context): ServiceState {
    val state = getPreferences(context).getString(key, ServiceState.DISABLED.name)
    return ServiceState.valueOf(state.toString())
}
