package com.shevikina.panzoomdemo.presentation.demo.imageviewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.shevikina.panzoomdemo.data.model.AndroidDecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedImage

/**
 * Приводит доменное изображение к платформенному и отдаёт обёртку для отрисовки.
 *
 * Абстракция поверх DrawScope встала бы в горячий цикл отрисовки и стоила бы кадров,
 * поэтому приведение сделано осознанно и заперто в одной функции.
 *
 * Это единственное и осознанное нарушение чистоты presentation слоя,
 * чтобы в domain слое не пролез [androidx.compose.ui.graphics].
 * Разрешение выдано по имени файла в задаче `:app:checkPresentationPurity`.
 * Переименование файла или второе такое приведение в другом месте уронят сборку.
 */
internal fun DecodedImage.toImageBitmap(): ImageBitmap = (this as AndroidDecodedImage).bitmap.asImageBitmap()
