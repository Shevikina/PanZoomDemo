package com.shevikina.panzoomdemo.data.datasource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.data.model.PreparedAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal class ImageAssetLocalDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: ICoroutineDispatchers,
) : IImageAssetLocalDataSource {

    override suspend fun prepareSeekableFile(assetName: String): PreparedAsset = withContext(dispatchers.io) {
        dropStaleCopiesOnce()
        val cacheFile = File(context.cacheDir, "$CACHE_PREFIX$assetName")
        val sizeBytes = readAssetSizeBytes(assetName)
        if (!isUsable(cacheFile, expectedSize = sizeBytes)) {
            copyAtomically(assetName, cacheFile)
            DemoLog.cold { "asset $assetName скопирован в cache: ${cacheFile.length()} bytes" }
        }
        PreparedAsset(cacheFile, sizeBytes)
    }

    /**
     * Копия пригодна, только если её размер совпадает с ассетом. Проверки «файл есть и он
     * не пустой» мало: оборванная на середине копия проходит её и роняет декодер позже,
     * в совершенно непонятном месте.
     */
    private fun isUsable(
        cacheFile: File,
        expectedSize: Long,
    ): Boolean = when {
        !cacheFile.exists() -> false
        cacheFile.length() == 0L -> false
        expectedSize > 0L && cacheFile.length() != expectedSize -> {
            DemoLog.warn("копия ${cacheFile.name} обрезана: ${cacheFile.length()} вместо $expectedSize")
            false
        }

        else -> true
    }

    /**
     * Пишем во временный файл и переименовываем: переименование в пределах одной файловой
     * системы атомарно, поэтому оборванное копирование не оставит полуфабрикат под целевым именем.
     */
    private fun copyAtomically(
        assetName: String,
        cacheFile: File,
    ) {
        cacheFile.parentFile?.mkdirs()
        val tmpFile = File(cacheFile.path + TMP_SUFFIX)
        try {
            context.assets.open(assetName).use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmpFile.renameTo(cacheFile)) error("Не удалось переименовать ${tmpFile.name}")
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Удаляет копии прошлых запусков один раз за процесс.
     *
     * В пределах сессии копии сохраняем,
     * чтобы переключение между уже открытыми ассетами было мгновенным.
     */
    private fun dropStaleCopiesOnce() {
        if (!staleCopiesDropped.compareAndSet(false, true)) return
        val removed = context.cacheDir
            .listFiles { file -> file.name.startsWith(CACHE_PREFIX) }
            .orEmpty()
            .count { it.delete() }
        if (removed > 0) DemoLog.cold { "удалено копий ассетов из прошлых запусков: $removed" }
    }

    override suspend fun readAssetSizeBytes(assetName: String): Long = withContext(dispatchers.io) {
        runCatching { context.assets.openFd(assetName).use { it.length } }
            .onSuccess { DemoLog.cold { "Image $assetName is open. Size: $it bytes" } }
            .onFailure { DemoLog.error("Image $assetName is NOT open. Error: ${it.message}") }
            .getOrDefault(UNKNOWN_SIZE)
    }

    override suspend fun decodeFullBitmap(assetName: String): Bitmap = withContext(dispatchers.io) {
        // Намеренно плохо: весь файл распаковывается в память целиком.
        context.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
            ?: error("Failed to decode $assetName")
    }

    private val staleCopiesDropped = AtomicBoolean(false)

    private companion object {
        const val CACHE_PREFIX = "tiled_"
        const val TMP_SUFFIX = ".tmp"
        const val UNKNOWN_SIZE = -1L
    }
}
