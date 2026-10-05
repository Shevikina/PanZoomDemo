package com.shevikina.panzoomdemo.domain.model

/**
 * Причина, по которой изображение не удалось показать.
 *
 * Тип и текст сохраняются как есть, потому что изображения разного размера падают по-разному.
 */
sealed interface ImageLoadError {

    /** Имя класса исключения, например `OutOfMemoryError`. */
    val type: String

    /** Текст исключения, если он был. */
    val message: String?

    /** Битмап не поместился в память: демонстрируемый результат наивного режима. */
    data class OutOfMemory(
        override val message: String?,
    ) : ImageLoadError {
        override val type: String = "OutOfMemoryError"
    }

    /** Любой другой сбой: например, ограничение на размер текстуры или битый файл. */
    data class Unknown(
        override val type: String,
        override val message: String?,
    ) : ImageLoadError

    companion object {

        /**
         * Классифицирует сбой загрузки.
         * `CancellationException` сюда попадать не должна: отмена не является ошибкой,
         * её обязан пробросить вызывающий код.
         */
        fun from(cause: Throwable): ImageLoadError = when (cause) {
            is OutOfMemoryError -> OutOfMemory(cause.message)
            else -> Unknown(
                type = cause::class.simpleName ?: DEFAULT_TYPE,
                message = cause.message,
            )
        }

        private const val DEFAULT_TYPE = "Throwable"
    }
}
