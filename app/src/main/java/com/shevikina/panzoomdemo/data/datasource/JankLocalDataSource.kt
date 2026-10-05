package com.shevikina.panzoomdemo.data.datasource

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class JankLocalDataSource @Inject constructor() : IJankLocalDataSource {

    private val pendingJanks = AtomicInteger(0)

    override fun recordJank() {
        pendingJanks.incrementAndGet()
    }

    override fun flushAndGet(): Int = pendingJanks.getAndSet(0)
}
