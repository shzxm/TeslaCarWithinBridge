package com.shzxm.teslabridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogBus {
    private val buffer = ArrayDeque<String>(500)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        if (buffer.size >= 500) buffer.removeFirst()
        buffer.addLast(line)
        val snapshot = buffer.joinToString("\n")
        listeners.forEach { it(snapshot) }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        listeners.forEach { it("") }
    }

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")

    fun subscribe(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun unsubscribe(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
