package com.shevikina.panzoomdemo.data.datasource

/** Показания памяти текущего процесса. */
internal interface IMemoryLocalDataSource {

    /** Занято в Java heap, МБ. */
    fun javaHeapMb(): Long

    /** Занято в native heap, МБ. С API 26+ сюда попадают пиксели битмапов. */
    fun nativeHeapMb(): Long

    /**
     * PSS процесса в МБ.
     *
     * Читает файлы из `/proc/self`, поэтому вызывается только на io-диспетчере.
     */
    suspend fun pssMb(): Long
}
