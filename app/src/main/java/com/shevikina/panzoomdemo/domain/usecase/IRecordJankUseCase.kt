package com.shevikina.panzoomdemo.domain.usecase

/** Регистрирует джанк-кадр, пришедший из JankStats. */
interface IRecordJankUseCase {

    operator fun invoke()
}
