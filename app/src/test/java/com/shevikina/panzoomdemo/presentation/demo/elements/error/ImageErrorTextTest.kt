package com.shevikina.panzoomdemo.presentation.demo.elements.error

import androidx.compose.ui.text.font.FontWeight
import com.shevikina.panzoomdemo.presentation.demo.model.ImageErrorUi
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImageErrorTextTest {

    @Test
    fun `assert result when error is absent`() {
        imageErrorText(PREFIX, error = null).text shouldBe PREFIX
    }

    @Test
    fun `assert result when error has message`() {
        val text = imageErrorText(PREFIX, ImageErrorUi(type = TYPE, message = MESSAGE))

        text.text shouldBe "$PREFIX $TYPE: $MESSAGE"
    }

    @Test
    fun `assert result when message is null`() {
        val text = imageErrorText(PREFIX, ImageErrorUi(type = TYPE, message = null))

        text.text shouldBe "$PREFIX $TYPE"
    }

    /** Пустое сообщение равнозначно отсутствующему: двоеточие в конце строки выглядит обрывком. */
    @Test
    fun `assert result when message is blank`() {
        val text = imageErrorText(PREFIX, ImageErrorUi(type = TYPE, message = "   "))

        text.text shouldBe "$PREFIX $TYPE"
    }

    @Test
    fun `assert, when {error is present, type is bold}`() {
        val text = imageErrorText(PREFIX, ImageErrorUi(type = TYPE, message = MESSAGE))

        val span = text.spanStyles.single()
        span.item.fontWeight shouldBe FontWeight.Bold
        text.text.substring(span.start, span.end) shouldBe TYPE
    }

    private companion object {
        const val PREFIX = "Ошибка загрузки изображения"
        const val TYPE = "OutOfMemoryError"
        const val MESSAGE = "Failed to allocate 3355443200 bytes"
    }
}
