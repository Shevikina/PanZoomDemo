package com.shevikina.panzoomdemo.data.datasource

import android.os.Debug
import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class MemoryLocalDataSource @Inject constructor(
    private val dispatchers: ICoroutineDispatchers,
) : IMemoryLocalDataSource {

    override fun javaHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
    }

    override fun nativeHeapMb(): Long = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB

    /**
     * Читает свежие числа PSS для оверлея.
     */
    override suspend fun pssMb(): Long = withContext(dispatchers.io) {
        runCatching { Debug.getPss() }.getOrDefault(0L) / BYTES_PER_KB
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L

        const val BYTES_PER_KB = 1_024L
    }
}
