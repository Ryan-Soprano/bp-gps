package com.example.bp_gps.util

object Ids {
    fun normalize(id: String?): String = id?.trim()?.uppercase() ?: ""
    fun isValid(id: String?): Boolean = normalize(id).isNotEmpty()
}
