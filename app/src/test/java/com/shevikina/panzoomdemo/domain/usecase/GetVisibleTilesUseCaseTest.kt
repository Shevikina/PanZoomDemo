package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.ImageSize
import com.shevikina.panzoomdemo.domain.model.TileGrid
import io.kotest.matchers.shouldBe
import org.junit.Test

class GetVisibleTilesUseCaseTest {

    private val useCase = GetVisibleTilesUseCase()
    private val grid = TileGrid(ImageSize(2048, 2048), tileSize = 512)

    @Test
    fun `should return only visible tiles when culling is enabled`() {
        val tiles = useCase(grid, ImageRect(0f, 0f, 500f, 500f), cullingEnabled = true, sampleSize = FULL)

        tiles.size shouldBe 1
    }

    @Test
    fun `should return whole grid when culling is disabled`() {
        val tiles = useCase(grid, ImageRect(0f, 0f, 500f, 500f), cullingEnabled = false, sampleSize = FULL)

        tiles.size shouldBe 16
    }

    @Test
    fun `should shrink the whole grid on a coarser level`() {
        val tiles = useCase(grid, ImageRect.EMPTY, cullingEnabled = false, sampleSize = QUARTER)

        // Клетка выросла вчетверо по каждой стороне, поэтому вместо 16 тайлов остался один.
        tiles.size shouldBe 1
    }

    private companion object {
        const val FULL = 1
        const val QUARTER = 4
    }
}
