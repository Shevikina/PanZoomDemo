package com.shevikina.panzoomdemo.data.tiling

import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.data.datasource.BitmapPoolLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.RegionDecoderLocalDataSource
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.tiling.ITileSource
import com.shevikina.panzoomdemo.domain.usecase.IOpenTiledImageUseCase
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Реализация use case открытия изображения.
 *
 * Лежит в `data`, а не в `domain/usecase`: единственная из use cases, которая сама создаёт
 * платформенные объекты - декодер поверх файла и пул битмапов. Интерфейс при этом объявлен
 * в `domain`, так что направление зависимостей не меняется.
 *
 * Без скоупа: своего состояния нет, всё уходит в возвращаемую сессию.
 */
internal class OpenTiledImageUseCase @Inject constructor(
    private val dispatchers: ICoroutineDispatchers,
) : IOpenTiledImageUseCase {

    /**
     * Открывает изображение и отдаёт тайловую сессию поверх него.
     *
     * Создание декодеров и декод overview-подложки идут на io, потому что на main они
     * занимают сотни миллисекунд и срывают кадр.
     */
    override suspend fun invoke(source: ImageSource): ITileSource = withContext(dispatchers.io) {
        val pool = BitmapPoolLocalDataSource().apply { prewarmFullTiles() }
        val decoder = RegionDecoderLocalDataSource(File(source.path), pool)
        AndroidTileSource(decoder, pool)
    }

    /**
     * Прогревает буферы полного тайла, чтобы первые декоды брали готовый буфер из пула,
     * а не аллоцировали свой.
     *
     * Класс размеров ровно один. В пирамиде клетка растёт вместе с уровнем детализации,
     * поэтому любой уровень декодируется в те же [TileGrid.DEFAULT_TILE_SIZE] выходных
     * пикселей. Прежний прогрев делился на четыре класса, и три из них стали мёртвыми.
     *
     * Краевые тайлы обрезаны по границе изображения и в прогрев не попадают: размеры у них
     * все разные, а доля мала.
     *
     * Количество считается от бюджета пула, чтобы при изменении
     * бюджета прогрев не съедал его целиком и не переставал иметь смысл.
     */
    private fun BitmapPoolLocalDataSource.prewarmFullTiles() {
        val side = TileGrid.DEFAULT_TILE_SIZE
        val bytesPerBitmap = side.toLong() * side * BYTES_PER_PIXEL
        val prewarmBytes = (maxBytes * PREWARM_BUDGET_SHARE).toLong()
        prewarm(width = side, height = side, count = (prewarmBytes / bytesPerBitmap).toInt())
    }

    private companion object {

        /**
         * Доля бюджета пула, которую занимает прогрев.
         *
         * Больше брать нельзя, потому что прогрев идёт при открытии изображения и каждый
         * лишний буфер задерживает первый кадр.
         */
        const val PREWARM_BUDGET_SHARE = 0.2

        const val BYTES_PER_PIXEL = 4
    }
}
