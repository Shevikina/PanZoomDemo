package com.shevikina.panzoomdemo.domain.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class TileGridTest {

    private val grid = TileGrid(imageSize = ImageSize(1000, 600), tileSize = TILE_SIZE)

    @Test
    fun `assert result when whole image is requested`() {
        val tiles = grid.allTiles(FULL)

        tiles shouldContainExactly listOf(
            TileRect(0, 0, 512, 512),
            TileRect(512, 0, 1000, 512),
            TileRect(0, 512, 512, 600),
            TileRect(512, 512, 1000, 600),
        )
    }

    @Test
    fun `should clip edge tiles to image bounds`() {
        val edgeTile = grid.allTiles(FULL).last()

        edgeTile.width shouldBe 1000 - 512
        edgeTile.height shouldBe 600 - 512
    }

    @Test
    fun `should return only intersecting tiles when viewport is small`() {
        val tiles = grid.visibleTiles(ImageRect(0f, 0f, 100f, 100f), FULL)

        tiles shouldContainExactly listOf(TileRect(0, 0, 512, 512))
    }

    @Test
    fun `should return empty list when viewport is outside image`() {
        grid.visibleTiles(ImageRect(-500f, -500f, -1f, -1f), FULL).shouldBeEmpty()
    }

    @Test
    fun `should return empty list when image has no size`() {
        TileGrid(ImageSize.EMPTY, TILE_SIZE).allTiles(FULL).shouldBeEmpty()
    }

    @Test
    fun `should grow the cell together with sample size`() {
        grid.cellSize(FULL) shouldBe TILE_SIZE
        grid.cellSize(QUARTER) shouldBe TILE_SIZE * QUARTER
    }

    @Test
    fun `should cover the same viewport with fewer tiles on a coarser level`() {
        val viewport = ImageRect(0f, 0f, 1000f, 600f)

        val full = grid.visibleTiles(viewport, FULL)
        val quarter = grid.visibleTiles(viewport, QUARTER)

        // Клетка 2048 px накрывает изображение 1000x600 целиком, а клетка 512 px режет его на четыре.
        full.size shouldBe 4
        quarter shouldContainExactly listOf(TileRect(0, 0, 1000, 600))
    }

    @Test
    fun `should clip edge tiles to image bounds on a coarser level`() {
        val tiles = TileGrid(ImageSize(5000, 600), TILE_SIZE).allTiles(HALF)

        // Клетка 1024 px, поэтому по ширине пять штук, и последняя обрезана.
        tiles.size shouldBe 5
        tiles.last() shouldBe TileRect(4096, 0, 5000, 600)
    }

    @Test
    fun `should not collide keys between levels`() {
        val viewport = ImageRect(0f, 0f, 1000f, 600f)

        val full = grid.visibleTiles(viewport, FULL).toSet()
        val half = grid.visibleTiles(viewport, HALF).toSet()

        // Кеш ключуется по TileRect, и слои разных уровней лежат в нём одновременно.
        full intersect half shouldBe emptySet()
    }

    private companion object {
        const val TILE_SIZE = 512
        const val FULL = 1
        const val HALF = 2
        const val QUARTER = 4
    }
}
