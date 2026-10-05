package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.test.stub.FakeDecodedImage
import io.kotest.matchers.shouldBe
import org.junit.Test

class TileCacheTest {

    private val cache = TileCache()

    @Test
    fun `should hold buffer for grace frames before returning it to pool`() {
        val image = FakeDecodedImage()
        cache.putActive(RECT_A, DrawableTile(image))

        cache.removeActive(RECT_A)

        image.releaseCount shouldBe 0
        repeat(GRACE_FRAMES - 1) { cache.drainReleasable() }
        image.releaseCount shouldBe 0
        cache.drainReleasable() shouldBe 1
        image.releaseCount shouldBe 1
    }

    @Test
    fun `should move active tiles to fallback and drop previous fallback`() {
        val old = FakeDecodedImage()
        val current = FakeDecodedImage()
        cache.putActive(RECT_A, DrawableTile(old))
        cache.promoteActiveToFallback()
        cache.putActive(RECT_B, DrawableTile(current))

        val promoted = cache.promoteActiveToFallback()

        promoted shouldBe 1
        cache.activeTiles.isEmpty() shouldBe true
        cache.fallbackTiles.keys shouldBe setOf(RECT_B)
        drainAll()
        old.releaseCount shouldBe 1
    }

    @Test
    fun `should release fallback only when active covers whole viewport`() {
        val fallback = FakeDecodedImage()
        cache.putActive(RECT_A, DrawableTile(fallback))
        cache.promoteActiveToFallback()
        cache.putActive(RECT_A, DrawableTile(FakeDecodedImage()))

        cache.releaseFallbackIfCovered(setOf(RECT_A, RECT_B)) shouldBe false
        cache.releaseFallbackIfCovered(setOf(RECT_A)) shouldBe true
        cache.fallbackTiles.isEmpty() shouldBe true
    }

    @Test
    fun `should cull fallback tiles outside viewport`() {
        cache.putActive(RECT_A, DrawableTile(FakeDecodedImage()))
        cache.putActive(RECT_B, DrawableTile(FakeDecodedImage()))
        cache.promoteActiveToFallback()

        cache.cullFallback(AREA_OVER_A) shouldBe true

        cache.fallbackTiles.keys shouldBe setOf(RECT_A)
    }

    @Test
    fun `should keep fallback of a coarser level that covers the viewport`() {
        // Подложка после смены LOD лежит клетками прежнего уровня, и с новыми ключами
        // они не совпадают ни одной. По ключам подложка улетела бы целиком.
        cache.putActive(COARSE_RECT, DrawableTile(FakeDecodedImage()))
        cache.promoteActiveToFallback()

        cache.cullFallback(AREA_OVER_A) shouldBe false

        cache.fallbackTiles.keys shouldBe setOf(COARSE_RECT)
    }

    @Test
    fun `should report whether fallback still intersects needed tiles`() {
        cache.putActive(RECT_A, DrawableTile(FakeDecodedImage()))
        cache.promoteActiveToFallback()

        cache.fallbackIntersects(AREA_OVER_A) shouldBe true
        cache.fallbackIntersects(AREA_OVER_B) shouldBe false
    }

    @Test
    fun `assert, when {cache disposed, waiting buffers released and layers cleared}`() {
        val waiting = FakeDecodedImage()
        cache.putActive(RECT_A, DrawableTile(waiting))
        cache.removeActive(RECT_A)
        cache.putActive(RECT_B, DrawableTile(FakeDecodedImage()))

        cache.dispose()

        waiting.releaseCount shouldBe 1
        cache.activeTiles.isEmpty() shouldBe true
        cache.releasePendingCount shouldBe 0
    }

    @Test
    fun `should keep every tile put into the active layer`() {
        // Бюджета у кеша нет: в чистом тайлинге активный слой растёт вместе с набором
        // нужных тайлов, а ограничивает его culling, а не вытеснение внутри кеша.
        rects(count = 64).forEach { rect -> cache.putActive(rect, DrawableTile(FakeDecodedImage())) }

        cache.activeTiles.size shouldBe 64
        cache.releasePendingCount shouldBe 0
    }

    @Test
    fun `should return replaced buffer to pool when the same rect is put again`() {
        val replaced = FakeDecodedImage()
        cache.putActive(RECT_A, DrawableTile(replaced))

        cache.putActive(RECT_A, DrawableTile(FakeDecodedImage()))

        cache.activeTiles.size shouldBe 1
        cache.releasePendingCount shouldBe 1
        drainAll()
        replaced.releaseCount shouldBe 1
    }

    @Test
    fun `should spread a mass release over several frames`() {
        // Так выглядит включение culling: активный слой схлопывается разом, и весь возврат
        // буферов вместе с вытеснением в пуле раньше вставал в один кадровый колбэк.
        val images = List(MAX_RELEASES_PER_FRAME + 10) { FakeDecodedImage() }
        val rects = rects(count = images.size)
        rects.forEachIndexed { index, rect -> cache.putActive(rect, DrawableTile(images[index])) }
        rects.forEach(cache::removeActive)

        repeat(GRACE_FRAMES) { cache.drainReleasable() }

        images.count { it.releaseCount == 1 } shouldBe MAX_RELEASES_PER_FRAME
        cache.releasePendingCount shouldBe 10
        cache.drainReleasable() shouldBe 10
        images.all { it.releaseCount == 1 } shouldBe true
    }

    private fun drainAll() = repeat(GRACE_FRAMES) { cache.drainReleasable() }

    private fun rects(count: Int): List<TileRect> = List(count) { index ->
        TileRect(index * TILE_SIDE, 0, (index + 1) * TILE_SIDE, TILE_SIDE)
    }

    private companion object {
        const val GRACE_FRAMES = 3
        const val MAX_RELEASES_PER_FRAME = 64
        const val TILE_SIDE = 512

        val RECT_A = TileRect(0, 0, 512, 512)
        val RECT_B = TileRect(512, 0, 1024, 512)

        /** Клетка уровня ss=4, накрывающая оба тайла полного разрешения. */
        val COARSE_RECT = TileRect(0, 0, 2048, 2048)

        val AREA_OVER_A = ImageRect(0f, 0f, 512f, 512f)
        val AREA_OVER_B = ImageRect(512f, 0f, 1024f, 512f)
    }
}
