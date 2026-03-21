package com.arto.app.di

import com.arto.app.BuildConfig
import com.arto.app.data.remote.AnthropicApiClient
import com.arto.app.domain.usecase.AnalyzeMessageUseCase
import org.koin.dsl.module

/**
 * AppModule — Koin dependency injection definitions.
 *
 * Provides singleton instances of the API client and
 * factory instances of use cases.
 */
val appModule = module {

    // ── Data layer ──────────────────────────────────────────────

    // Singleton: one HTTP client shared across the app lifetime
    single { AnthropicApiClient(apiKey = BuildConfig.ANTHROPIC_API_KEY) }

    // ── Domain layer ────────────────────────────────────────────

    // Factory: new instance per call site (use cases are lightweight)
    factory { AnalyzeMessageUseCase(apiClient = get()) }
}