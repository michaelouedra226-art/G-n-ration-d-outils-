package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AiGateway
import com.example.core.ai.AiRequest
import com.example.core.ai.AiResponse
import com.example.core.ai.ApiRouterService
import com.example.core.ai.ConnectionTestResult
import com.example.core.ai.ForgeAutonomyMode
import com.example.core.ai.ForgePipelineEvent
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.ai.MultiAgentForgePipeline
import com.example.core.ai.ProviderType
import com.example.core.ai.ResolvedEndpoint
import com.example.core.data.local.ForgeDatabase
import com.example.core.data.repository.ApiManagerRepository
import com.example.core.data.repository.ToolRepository
import com.example.core.security.SecretCipher
import com.example.runtime.GeneratedPdfArtifact
import com.example.runtime.PdfGenerator
import com.example.runtime.SandboxedToolRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomousForgePipelineTest {

    private lateinit var db: ForgeDatabase
    private lateinit var apiRepo: ApiManagerRepository
    private lateinit var runtime: SandboxedToolRuntime

    @Before
    fun setup() {
        val directExecutor = Executor { command -> command.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ForgeDatabase::class.java
        )
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .allowMainThreadQueries()
            .build()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(encodedPayload: String): String = encodedPayload
        }
        apiRepo = ApiManagerRepository(db.apiManagerDao(), cipher)
        val toolRepo = ToolRepository(db.toolDao())
        val noOpGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint) =
                Result.failure<AiResponse>(IllegalStateException("Unused"))
            override suspend fun testConnection(endpoint: ResolvedEndpoint) =
                ConnectionTestResult(false, 0, "Unused")
            override suspend fun fetchAvailableModels(endpoint: ResolvedEndpoint) =
                Result.success(emptyList<String>())
        }
        runtime = SandboxedToolRuntime(
            context = null,
            toolRepository = toolRepo,
            apiRouterService = ApiRouterService(apiRepo, noOpGateway),
            pdfGenerator = object : PdfGenerator {
                override suspend fun generatePdf(title: String, body: String, subtitle: String) =
                    Result.failure<GeneratedPdfArtifact>(IllegalStateException("Unused"))
            }
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `autonomous mode plans a multi-plugin suite and self-repairs QA dry-run failure`() = runTest {
        val provider = apiRepo.addProvider(
            name = "OpenAI",
            providerType = ProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            modelPriority = listOf("gpt-4o-mini"),
            priority = 1
        ).getOrThrow()
        apiRepo.addApiKey(provider.id, "Main", "sk-test-1234567890", priority = 1).getOrThrow()

        var callIndex = 0
        val scriptedGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint): Result<AiResponse> {
                callIndex++
                val content = when (callIndex) {
                    // 1. Autonomous Architect Plan -> SUITE of 2 tools
                    1 -> """
                        {
                          "mode": "SUITE",
                          "rationale": "Un outil de calcul TVA et un générateur de mot de passe",
                          "plugins": [
                            {"title": "Calculateur TVA", "spec": "Calcule TTC depuis HT"},
                            {"title": "Générateur Clé", "spec": "Génère un mot de passe robuste"}
                          ]
                        }
                    """.trimIndent()
                    // 2. First manifest intentionally fails dry-run (unknown variable `missing_var`)
                    2 -> """
                        {
                          "id": "tmp",
                          "name": "Calculateur TVA",
                          "description": "Calcule le TTC",
                          "version": "1.0.0",
                          "author": "User",
                          "category": "CALCULATOR",
                          "status": "ACTIVE",
                          "permissions": [],
                          "fields": [{"id": "ht", "label": "HT", "type": "NUMBER", "required": true}],
                          "outputs": [{"id": "out", "label": "TTC", "type": "CALCULATION_RESULT", "sourceVariable": "result"}],
                          "workflow": [{"id": "s1", "type": "SANDBOX_EXPRESSION", "title": "Calcul", "expression": "missing_var * 1.2", "outputVariable": "result"}]
                        }
                    """.trimIndent()
                    // 3. Corrector auto-repair fixes expression to `ht * 1.2`
                    3 -> """
                        {
                          "id": "tmp",
                          "name": "Calculateur TVA",
                          "description": "Calcule le TTC",
                          "version": "1.0.0",
                          "author": "User",
                          "category": "CALCULATOR",
                          "status": "ACTIVE",
                          "permissions": [],
                          "fields": [{"id": "ht", "label": "HT", "type": "NUMBER", "required": true}],
                          "outputs": [{"id": "out", "label": "TTC", "type": "CALCULATION_RESULT", "sourceVariable": "result"}],
                          "workflow": [{"id": "s1", "type": "SANDBOX_EXPRESSION", "title": "Calcul", "expression": "ht * 1.2", "outputVariable": "result"}]
                        }
                    """.trimIndent()
                    // 4. Second plugin manifest succeeds immediately
                    else -> """
                        {
                          "id": "tmp2",
                          "name": "Générateur Clé",
                          "description": "Génère un mot de passe fort",
                          "version": "1.0.0",
                          "author": "User",
                          "category": "DEVELOPER",
                          "status": "ACTIVE",
                          "permissions": [],
                          "fields": [{"id": "len", "label": "Longueur", "type": "NUMBER", "defaultValue": "16", "required": true}],
                          "outputs": [{"id": "out", "label": "Clé", "type": "TEXT_OUTPUT", "sourceVariable": "pwd"}],
                          "workflow": [{"id": "s1", "type": "TEXT_TRANSFORM", "title": "Gen", "operation": "PASSWORD_GEN", "operationParam": "{{len}}", "outputVariable": "pwd"}]
                        }
                    """.trimIndent()
                }
                return Result.success(
                    AiResponse(
                        content = content,
                        modelUsed = endpoint.model,
                        providerName = endpoint.providerName,
                        keyLabel = endpoint.keyLabel,
                        latencyMs = 50L,
                        tokensUsed = 100L
                    )
                )
            }

            override suspend fun testConnection(endpoint: ResolvedEndpoint) =
                ConnectionTestResult(true, 40L, "OK")

            override suspend fun fetchAvailableModels(endpoint: ResolvedEndpoint) =
                Result.success(listOf("gpt-4o-mini"))
        }

        val pipeline = MultiAgentForgePipeline(
            apiRouterService = ApiRouterService(apiRepo, scriptedGateway),
            dryRunVerifier = { manifest, sample -> runtime.dryRunDeterministic(manifest, sample) }
        )

        val events = pipeline.forgeAutonomously(
            userPrompt = "Crée un calculateur TVA et un générateur de mot de passe",
            authorName = "Testeur",
            strategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION,
            autonomyMode = ForgeAutonomyMode.AUTONOMOUS
        ).toList()

        val success = events.filterIsInstance<ForgePipelineEvent.Success>().single()
        assertEquals(2, success.allManifests.size)
        assertEquals("Calculateur TVA", success.allManifests[0].name)
        assertEquals("Générateur Clé", success.allManifests[1].name)
        assertTrue(callIndex == 4)
    }
}
