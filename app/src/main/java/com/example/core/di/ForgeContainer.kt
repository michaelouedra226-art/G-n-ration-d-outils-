package com.example.core.di

import android.content.Context
import com.example.core.ai.AndroidNetworkChecker
import com.example.core.ai.ApiRouterService
import com.example.core.ai.BuiltInGeminiNoticeGateway
import com.example.core.ai.MultiAgentForgePipeline
import com.example.core.ai.OkHttpAiGateway
import com.example.core.data.local.ForgeDatabase
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.repository.ApiManagerRepository
import com.example.core.data.repository.ToolRepository
import com.example.core.security.AndroidKeystoreCipher
import com.example.runtime.AndroidPdfGeneratorService
import com.example.runtime.SandboxedToolRuntime
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Lightweight manual Dependency Injection container scoped to [com.example.ForgeApplication].
 */
class ForgeContainer(context: Context) {
    private val appContext = context.applicationContext

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val database: ForgeDatabase = ForgeDatabase.create(appContext)
    val secretCipher = AndroidKeystoreCipher()
    val preferencesRepository = AppPreferencesRepository(appContext)

    val toolRepository = ToolRepository(
        toolDao = database.toolDao(),
        moshi = moshi
    )

    val apiManagerRepository = ApiManagerRepository(
        apiManagerDao = database.apiManagerDao(),
        secretCipher = secretCipher
    )

    val networkChecker = AndroidNetworkChecker(appContext)
    val builtInGeminiNotice = BuiltInGeminiNoticeGateway()
    val aiGateway = OkHttpAiGateway(networkChecker = networkChecker)

    val apiRouterService = ApiRouterService(
        apiManagerRepository = apiManagerRepository,
        aiGateway = aiGateway,
        builtInGeminiNotice = builtInGeminiNotice
    )

    val multiAgentForgePipeline = MultiAgentForgePipeline(
        apiRouterService = apiRouterService,
        moshi = moshi
    )

    val pdfGenerator = AndroidPdfGeneratorService(appContext)

    val sandboxedToolRuntime = SandboxedToolRuntime(
        context = appContext,
        toolRepository = toolRepository,
        apiRouterService = apiRouterService,
        pdfGenerator = pdfGenerator
    )
}
