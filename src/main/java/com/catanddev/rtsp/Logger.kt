package com.catanddev.rtsp

object Logger {
    fun dumbLog(message: String) {
        val timestamp = System.currentTimeMillis()
        // This is a simple logging function that should be used with debug conditions
        println("[$timestamp ms] $message")
    }
}