package com.shevikina.panzoomdemo.data.mapper

import com.shevikina.panzoomdemo.domain.model.TileRect
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric нужен потому, что android.graphics.Rect в обычном unit-тесте - заглушка.
 *
 * Это единственный тест на Robolectric в проекте, и он остаётся: тесты мапперов обязательны
 * по правилам проекта, а без настоящего Rect проверять в этом маппере нечего. Отсюда же
 * требование прогонять юнит-тесты на JDK 21 (см. app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
class TileMapperTest {

    @Test
    fun `assert result`() {
        val rect = TileRect(left = 512, top = 1024, right = 1024, bottom = 1536).toAndroid()

        rect.left shouldBe 512
        rect.top shouldBe 1024
        rect.right shouldBe 1024
        rect.bottom shouldBe 1536
    }

    @Test
    fun `should keep clipped edge tile size`() {
        val rect = TileRect(left = 1024, top = 512, right = 1200, bottom = 600).toAndroid()

        rect.width() shouldBe 176
        rect.height() shouldBe 88
    }
}
