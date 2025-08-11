package com.example.bp_gps.model

data class DispatchMessage(
    val address: String = "",
    val policeId: String? = null
)
{
    override fun toString(): String {
        return "DispatchMessage(address='$address', policeId='$policeId')"
    }
}