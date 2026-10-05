package com.shevikina.panzoomdemo.data.datasource

/**
 * Мост между JankStats и оверлеем метрик.
 *
 * JankStats вызывает [recordJank] с фонового потока на каждый джанк-кадр, а оверлей раз
 * в секунду забирает накопленное через [flushAndGet] уже с main.
 */
internal interface IJankLocalDataSource {

    fun recordJank()

    /** Возвращает накопленное количество джанков и обнуляет счётчик. */
    fun flushAndGet(): Int
}
