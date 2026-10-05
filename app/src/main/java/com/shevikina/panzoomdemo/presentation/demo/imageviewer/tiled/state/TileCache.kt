package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TileCache.Companion.GRACE_FRAMES
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TileCache.Companion.MAX_RELEASES_PER_FRAME

/**
 * Двухслойный кеш тайлов. Актуальный LOD лежит поверх подложки из предыдущего.
 *
 * Все мутации строго с main-потока.
 *
 * Основана на [LinkedHashMap], а не SnapshotStateMap, чтобы мутации не запускали перерисовку сами.
 * Canvas инвалидируется вручную через revision в конце пакета изменений.
 *
 * Бюджета у кеша нет намеренно.
 */
internal class TileCache {

    /** Тайлы текущего LOD. */
    val activeTiles: LinkedHashMap<TileRect, DrawableTile> = LinkedHashMap()

    /**
     * Подложка из тайлов предыдущего LOD.
     *
     * Рисуется под новыми, пока новый LOD не покроет viewport,
     * чтобы при смене inSampleSize экран не моргал.
     */
    val fallbackTiles: LinkedHashMap<TileRect, DrawableTile> = LinkedHashMap()

    /**
     * Изображения ждут [GRACE_FRAMES] кадров перед возвратом в пул: Skia может держать
     * ссылку в display list, и перезапись буфера через inBitmap даёт артефакты.
     */
    private val releasePending = ArrayDeque<DeferredRelease>()

    private var frameCounter = 0

    val releasePendingCount: Int
        get() = releasePending.size

    /** Кладёт тайл в активный слой. */
    fun putActive(
        rect: TileRect,
        tile: DrawableTile,
    ) {
        activeTiles.put(rect, tile)?.let(::releaseLater)
    }

    /** Убирает тайл из активного слоя, отложив возврат буфера в пул. */
    fun removeActive(rect: TileRect) {
        activeTiles.remove(rect)?.let(::releaseLater)
    }

    /**
     * Переводит активный слой в подложку, а прежнюю подложку отправляет на возврат.
     *
     * Вызывается при смене LOD.
     *
     * @return количество тайлов, ставших подложкой.
     */
    fun promoteActiveToFallback(): Int {
        fallbackTiles.values.forEach(::releaseLater)
        fallbackTiles.clear()

        val promoted = activeTiles.size

        fallbackTiles.putAll(activeTiles)
        activeTiles.clear()
        return promoted
    }

    /** Определяет, есть ли в подложке хоть один тайл внутри [area], чтобы потом её сохранить. */
    fun fallbackIntersects(area: ImageRect): Boolean = fallbackTiles.keys.any { it.intersects(area) }

    fun dropFallback() {
        fallbackTiles.values.forEach(::releaseLater)
        fallbackTiles.clear()
    }

    /**
     * Выбрасывает из подложки тайлы вне [area].
     *
     * Сравниваем геометрией, а не ключами, потому что подложка пришла с прежнего уровня
     * и её прямоугольники другого размера. По ключам не совпал бы ни один, и подложка
     * улетала бы целиком на первом же пересчёте после смены LOD.
     *
     * @return true, если подложка изменилась (нужна инвалидация Canvas).
     */
    fun cullFallback(area: ImageRect): Boolean {
        val stale = fallbackTiles.keys.filterNot { it.intersects(area) }
        if (stale.isEmpty()) return false

        stale.forEach { key -> fallbackTiles.remove(key)?.let(::releaseLater) }
        DemoLog.hot { "fallback culled: ${stale.size} rects out of viewport (remain=${fallbackTiles.size})" }
        return true
    }

    /**
     * Если активный слой полностью покрывает [needed], то подложка больше не нужна.
     * @return true, если подложка отпущена.
     */
    fun releaseFallbackIfCovered(needed: Set<TileRect>): Boolean {
        if (fallbackTiles.isEmpty() || needed.isEmpty()) return false
        if (!activeTiles.keys.containsAll(needed)) return false

        val released = fallbackTiles.size
        dropFallback()
        DemoLog.hot { "fallback released: $released tiles (current LOD covers viewport)" }
        return true
    }

    /**
     * Возвращает в пул изображения, отлежавшие [GRACE_FRAMES] кадров.
     *
     * Зовётся из кадрового цикла внутри `withFrameMillis` (не чаще раза в кадр):
     * выдержка не может оказаться короче, даже когда цикл спит.
     *
     * За один заход отдаёт не больше [MAX_RELEASES_PER_FRAME].
     */
    fun drainReleasable(): Int {
        frameCounter++
        var drained = 0
        while (drained < MAX_RELEASES_PER_FRAME &&
            releasePending.isNotEmpty() &&
            frameCounter - releasePending.first().frameId >= GRACE_FRAMES
        ) {
            releasePending.removeFirst().tile.release()
            drained++
        }
        return drained
    }

    /**
     * Дропает ссылки без возврата в пул, потому что HWUI может держать активные тайлы для текущего кадра.
     * После рендера GC освободит память.
     */
    fun dispose() {
        releasePending.forEach { it.tile.release() }
        releasePending.clear()
        activeTiles.clear()
        fallbackTiles.clear()
    }

    private fun releaseLater(tile: DrawableTile) {
        releasePending.addLast(DeferredRelease(frameCounter, tile))
    }

    private data class DeferredRelease(
        val frameId: Int,
        val tile: DrawableTile,
    )

    private companion object {
        /**
         * Выдержка в кадрах перед возвратом буфера в пул.
         *
         * Сразу отпускать буфер нельзя, потому что HWUI держит в конвеере до трёх кадров:
         * один показывается, другой композится и последний еще рисуется.
         * Ссылка на каждый такой битмап живет дольше чем запись о кадре в кеше,
         * так что без выдержки пул успеет отдать буффер под декод.
         * Как итог, на месте старого, еще не отрисованного, тайла окажется чужой.
         *
         * Выдержка в 3 кадра покрывает всю глубину конвейера HWUI.
         * */
        const val GRACE_FRAMES = 3

        /**
         * Потолок на число возвратов буферов в пул за один кадр.
         *
         * Включение куллинга вызывает массовый возврат битмапов из невидимой зоны в [BitmapPoolLocalDataSource].
         * Это может вызвать рывок на экране, потому что главный поток застревает на кадровом колбеке
         * и кадр или несколько по итогу выпадают при отрисовке.
         *
         * Установка потолка размазывает работу на несколько кадров,
         * и каждый из них унесёт столько, сколько успевает сделать за свой бюджет времени.
         * */
        const val MAX_RELEASES_PER_FRAME = 64
    }
}
