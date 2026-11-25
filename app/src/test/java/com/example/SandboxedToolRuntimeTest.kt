package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AiGateway
import com.example.core.ai.AiMessage
import com.example.core.ai.AiRequest
import com.example.core.ai.AiResponse
import com.example.core.ai.ApiRouterService
import com.example.core.ai.ConnectionTestResult
import com.example.core.ai.NetworkChecker
import com.example.core.ai.ProviderType
import com.example.core.ai.ResolvedEndpoint
import com.example.core.data.local.ForgeDatabase
import com.example.core.data.repository.ApiManagerRepository
import com.example.core.data.repository.ToolRepository
import com.example.core.security.SecretCipher
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.UiFieldSpec
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.UiOutputSpec
import com.example.domain.manifest.UiOutputType
import com.example.domain.manifest.WorkflowStepSpec
import com.example.domain.manifest.WorkflowStepType
import com.example.runtime.GeneratedPdfArtifact
import com.example.runtime.PdfGenerator
import com.example.runtime.SandboxedToolRuntime
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SandboxedToolRuntimeTest {

    private lateinit var db: ForgeDatabase
    private lateinit var toolRepo: ToolRepository
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
        toolRepo = ToolRepository(db.toolDao())

        val passCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(encodedPayload: String): String = encodedPayload
        }
        val apiRepo = ApiManagerRepository(db.apiManagerDao(), passCipher)
        val noOpGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint) =
                Result.failure<AiResponse>(IllegalStateException("Unused"))
            override suspend fun testConnection(endpoint: ResolvedEndpoint) =
                ConnectionTestResult(false, 0, "Unused")
            override suspend fun fetchAvailableModels(endpoint: ResolvedEndpoint) =
                Result.success(emptyList<String>())
        }
        val fakePdf = object : PdfGenerator {
            override suspend fun generatePdf(title: String, body: String, subtitle: String) =
                Result.success(GeneratedPdfArtifact("/tmp/test.pdf", "test.pdf", 1, 2048L))
        }
        runtime = SandboxedToolRuntime(
            context = null,
            toolRepository = toolRepo,
            apiRouterService = ApiRouterService(apiRepo, noOpGateway),
            pdfGenerator = fakePdf
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `executes deterministic calculator with isolated storage and pdf export`() = runBlocking {
        val manifest = ToolManifest(
            id = "tool_calc_1",
            name = "Calcul TVA & PDF",
            description = "Calcule le montant TTC et génère un PDF",
            category = ToolCategory.CALCULATOR,
            permissions = listOf(ToolPermission.LOCAL_STORAGE, ToolPermission.PDF_EXPORT),
            fields = listOf(
                UiFieldSpec(id = "ht", label = "Montant HT", type = UiFieldType.NUMBER),
                UiFieldSpec(id = "tva", label = "TVA (%)", type = UiFieldType.NUMBER)
            ),
            outputs = listOf(
                UiOutputSpec(id = "out_val", label = "Montant TTC", type = UiOutputType.CALCULATION_RESULT, sourceVariable = "result"),
                UiOutputSpec(id = "out_pdf", label = "PDF", type = UiOutputType.PDF_PREVIEW_EXPORT, sourceVariable = "pdf_info")
            ),
            workflow = listOf(
                WorkflowStepSpec(id = "s1", type = WorkflowStepType.VALIDATE_INPUT, title = "Validation", outputVariable = "ok"),
                WorkflowStepSpec(
                    id = "s2",
                    type = WorkflowStepType.SANDBOX_EXPRESSION,
                    title = "Calcul TTC",
                    outputVariable = "result",
                    expression = "ht * (1 + tva / 100)"
                ),
                WorkflowStepSpec(
                    id = "s3",
                    type = WorkflowStepType.TOOL_STORAGE_SAVE,
                    title = "Sauvegarde dernière valeur",
                    outputVariable = "saved",
                    storageKey = "last_ttc",
                    template = "{{result}}"
                ),
                WorkflowStepSpec(
                    id = "s4",
                    type = WorkflowStepType.GENERATE_PDF,
                    title = "Export PDF",
                    outputVariable = "pdf_info",
                    template = "Montant TTC : {{result}}"
                )
            )
        )

        toolRepo.saveTool(manifest, "Init").getOrThrow()

        val result = runtime.execute(
            manifest = manifest,
            rawInputs = mapOf("ht" to "100", "tva" to "20")
        )

        assertTrue(result.success)
        assertEquals("120", result.outputs["out_val"])
        assertNotNull(result.pdfArtifact)
        assertEquals("120", toolRepo.readSandboxedStorage("tool_calc_1", "last_ttc"))
    }

    @Test
    fun `forwards multi-turn conversationHistory to AI_PROMPT step`() = runBlocking {
        val passCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(encodedPayload: String): String = encodedPayload
        }
        val apiRepo = ApiManagerRepository(db.apiManagerDao(), passCipher)
        val provider = apiRepo.addProvider(
            name = "OpenAI",
            providerType = ProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            modelPriority = listOf("gpt-4o-mini"),
            priority = 1
        ).getOrThrow()
        apiRepo.addApiKey(provider.id, "Primary", "sk-test-1234567890", priority = 1).getOrThrow()

        var capturedHistory: List<AiMessage> = emptyList()
        val recordingGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint): Result<AiResponse> {
                capturedHistory = request.conversationHistory
                return Result.success(
                    AiResponse(
                        content = "Réponse contextuelle",
                        modelUsed = endpoint.model,
                        providerName = endpoint.providerName,
                        keyLabel = endpoint.keyLabel,
                        latencyMs = 80L,
                        tokensUsed = 30L
                    )
                )
            }

            override suspend fun testConnection(endpoint: ResolvedEndpoint) =
                ConnectionTestResult(true, 50L, "OK")

            override suspend fun fetchAvailableModels(endpoint: ResolvedEndpoint) =
                Result.success(listOf("gpt-4o-mini"))
        }

        val chatRuntime = SandboxedToolRuntime(
            context = null,
            toolRepository = toolRepo,
            apiRouterService = ApiRouterService(apiRepo, recordingGateway),
            pdfGenerator = object : PdfGenerator {
                override suspend fun generatePdf(title: String, body: String, subtitle: String) =
                    Result.failure<GeneratedPdfArtifact>(IllegalStateException("Unused"))
            }
        )

        val chatManifest = ToolManifest(
            id = "tool_chat_1",
            name = "Assistant conversationnel",
            description = "Assistant multi-tours",
            category = ToolCategory.AI_ASSISTANT,
            permissions = listOf(ToolPermission.AI_INFERENCE),
            fields = listOf(
                UiFieldSpec(id = "message", label = "Message", type = UiFieldType.MULTILINE_TEXT)
            ),
            outputs = listOf(
                UiOutputSpec(id = "reply", label = "Réponse", type = UiOutputType.CHAT_TRANSCRIPT, sourceVariable = "ai_reply")
            ),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "s1",
                    type = WorkflowStepType.AI_PROMPT,
                    title = "Appel IA",
                    outputVariable = "ai_reply",
                    systemPrompt = "Tu es concis.",
                    template = "{{message}}"
                )
            )
        )
        toolRepo.saveTool(chatManifest, "Init").getOrThrow()

        val history = listOf(
            AiMessage(role = "user", content = "Bonjour"),
            AiMessage(role = "assistant", content = "Bonjour ! Comment puis-je aider ?")
        )
        val result = chatRuntime.execute(
            manifest = chatManifest,
            rawInputs = mapOf("message" to "Résume notre échange"),
            conversationHistory = history
        )

        assertTrue(result.success)
        assertEquals("Réponse contextuelle", result.outputs["reply"])
        assertEquals(history, capturedHistory)
    }

    @Test
    fun `executes CONDITION_BRANCH, TEXT_TRANSFORM, and DATE_TIME deterministically`() = runBlocking {
        val manifest = ToolManifest(
            id = "tool_logic_suite",
            name = "Atelier Logique",
            description = "Test branches, texte, dates",
            category = ToolCategory.DEVELOPER,
            fields = listOf(
                UiFieldSpec(id = "score", label = "Score", type = UiFieldType.NUMBER),
                UiFieldSpec(id = "raw_text", label = "Texte", type = UiFieldType.TEXT),
                UiFieldSpec(id = "base_date", label = "Date", type = UiFieldType.TEXT)
            ),
            outputs = listOf(
                UiOutputSpec(id = "verdict", label = "Verdict", type = UiOutputType.TEXT_OUTPUT, sourceVariable = "branch_out"),
                UiOutputSpec(id = "hash", label = "SHA256", type = UiOutputType.TEXT_OUTPUT, sourceVariable = "hash_out"),
                UiOutputSpec(id = "next_day", label = "Date +7j", type = UiOutputType.TEXT_OUTPUT, sourceVariable = "date_out"),
                UiOutputSpec(id = "pass", label = "Mot de passe", type = UiOutputType.TEXT_OUTPUT, sourceVariable = "pwd_out")
            ),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "c1",
                    type = WorkflowStepType.CONDITION_BRANCH,
                    title = "Seuil",
                    outputVariable = "branch_out",
                    expression = "score >= 50",
                    thenTemplate = "Admis ({{score}})",
                    elseTemplate = "Refusé ({{score}})"
                ),
                WorkflowStepSpec(
                    id = "t1",
                    type = WorkflowStepType.TEXT_TRANSFORM,
                    title = "Hash SHA-256",
                    outputVariable = "hash_out",
                    operation = "SHA256",
                    template = "{{raw_text}}"
                ),
                WorkflowStepSpec(
                    id = "d1",
                    type = WorkflowStepType.DATE_TIME,
                    title = "Ajout 7 jours",
                    outputVariable = "date_out",
                    operation = "ADD_DAYS",
                    operationParam = "7",
                    template = "{{base_date}}"
                ),
                WorkflowStepSpec(
                    id = "p1",
                    type = WorkflowStepType.TEXT_TRANSFORM,
                    title = "Mot de passe",
                    outputVariable = "pwd_out",
                    operation = "PASSWORD_GEN",
                    operationParam = "14"
                )
            )
        )
        toolRepo.saveTool(manifest, "Init").getOrThrow()

        val result = runtime.execute(
            manifest = manifest,
            rawInputs = mapOf("score" to "82", "raw_text" to "abc", "base_date" to "2026-01-10")
        )

        assertTrue(result.success)
        assertEquals("Admis (82)", result.outputs["verdict"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.outputs["hash"])
        assertEquals("2026-01-17", result.outputs["next_day"])
        assertEquals(14, result.outputs["pass"]?.length)
    }

    @Test
    fun `persists and aggregates items via LIST_STORAGE_APPEND and LIST_STORAGE_READ`() = runBlocking {
        val trackerManifest = ToolManifest(
            id = "tool_expenses",
            name = "Suivi Dépenses",
            description = "Journal de dépenses cumulatif",
            category = ToolCategory.PRODUCTIVITY,
            permissions = listOf(ToolPermission.LOCAL_STORAGE),
            fields = listOf(
                UiFieldSpec(id = "label", label = "Libellé", type = UiFieldType.TEXT),
                UiFieldSpec(id = "amount", label = "Montant", type = UiFieldType.NUMBER)
            ),
            outputs = listOf(
                UiOutputSpec(id = "list", label = "Entrées", type = UiOutputType.LIST_ITEMS, sourceVariable = "items_json"),
                UiOutputSpec(id = "total", label = "Total", type = UiOutputType.CALCULATION_RESULT, sourceVariable = "total_sum")
            ),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "a1",
                    type = WorkflowStepType.LIST_STORAGE_APPEND,
                    title = "Ajouter dépense",
                    storageKey = "expenses",
                    outputVariable = "items_json",
                    template = "{\"label\":\"{{label}}\",\"amount\":{{amount}}}"
                ),
                WorkflowStepSpec(
                    id = "r1",
                    type = WorkflowStepType.LIST_STORAGE_READ,
                    title = "Somme totale",
                    storageKey = "expenses",
                    operation = "SUM",
                    operationParam = "amount",
                    outputVariable = "total_sum"
                )
            )
        )
        toolRepo.saveTool(trackerManifest, "Init").getOrThrow()

        runtime.execute(trackerManifest, mapOf("label" to "Café", "amount" to "4.5"))
        val second = runtime.execute(trackerManifest, mapOf("label" to "Déjeuner", "amount" to "15.5"))

        assertTrue(second.success)
        assertEquals("20", second.outputs["total"])
        assertTrue(second.outputs["list"].orEmpty().contains("Déjeuner"))
    }

    @Test
    fun `executes HTTP_REQUEST with json extract and blocks SSRF private URLs`() = runBlocking {
        val passCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(encodedPayload: String): String = encodedPayload
        }
        val apiRepo = ApiManagerRepository(db.apiManagerDao(), passCipher)
        val noOpGateway = object : AiGateway {
            override suspend fun complete(request: AiRequest, endpoint: ResolvedEndpoint) =
                Result.failure<AiResponse>(IllegalStateException("Unused"))
            override suspend fun testConnection(endpoint: ResolvedEndpoint) =
                ConnectionTestResult(false, 0, "Unused")
            override suspend fun fetchAvailableModels(endpoint: ResolvedEndpoint) =
                Result.success(emptyList<String>())
        }
        val interceptedClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"bitcoin":{"eur":88500}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val netRuntime = SandboxedToolRuntime(
            context = null,
            toolRepository = toolRepo,
            apiRouterService = ApiRouterService(apiRepo, noOpGateway),
            pdfGenerator = object : PdfGenerator {
                override suspend fun generatePdf(title: String, body: String, subtitle: String) =
                    Result.failure<GeneratedPdfArtifact>(IllegalStateException("Unused"))
            },
            httpClient = interceptedClient,
            networkChecker = object : NetworkChecker {
                override fun isOnline(): Boolean = true
            }
        )

        val cryptoManifest = ToolManifest(
            id = "tool_crypto",
            name = "Prix BTC",
            description = "Prix temps réel",
            permissions = listOf(ToolPermission.NETWORK_FETCH),
            fields = listOf(UiFieldSpec(id = "coin", label = "Crypto", type = UiFieldType.TEXT)),
            outputs = listOf(UiOutputSpec(id = "price", label = "Prix EUR", type = UiOutputType.CALCULATION_RESULT, sourceVariable = "btc_eur")),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "h1",
                    type = WorkflowStepType.HTTP_REQUEST,
                    title = "Fetch API",
                    urlTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={{coin}}&vs_currencies=eur",
                    httpMethod = "GET",
                    operationParam = "bitcoin.eur",
                    outputVariable = "btc_eur"
                )
            )
        )
        toolRepo.saveTool(cryptoManifest, "Init").getOrThrow()

        val ok = netRuntime.execute(cryptoManifest, mapOf("coin" to "bitcoin"))
        assertTrue(ok.success)
        assertEquals("88500", ok.outputs["price"])

        val ssrfManifest = cryptoManifest.copy(
            id = "tool_ssrf",
            fields = listOf(UiFieldSpec(id = "host", label = "Host", type = UiFieldType.TEXT)),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "h_bad",
                    type = WorkflowStepType.HTTP_REQUEST,
                    title = "SSRF attempt",
                    urlTemplate = "https://{{host}}/secret",
                    outputVariable = "btc_eur"
                )
            )
        )
        toolRepo.saveTool(ssrfManifest, "Init").getOrThrow()
        val blocked = netRuntime.execute(ssrfManifest, mapOf("host" to "127.0.0.1"))
        assertFalse(blocked.success)
    }

    @Test
    fun `composes installed tools via CALL_TOOL`() = runBlocking {
        val childCalc = ToolManifest(
            id = "child_double",
            name = "Doubleur",
            description = "Double un nombre",
            fields = listOf(UiFieldSpec(id = "x", label = "X", type = UiFieldType.NUMBER)),
            outputs = listOf(UiOutputSpec(id = "out", label = "Résultat", type = UiOutputType.CALCULATION_RESULT, sourceVariable = "res")),
            workflow = listOf(
                WorkflowStepSpec(id = "s1", type = WorkflowStepType.SANDBOX_EXPRESSION, title = "x2", expression = "x * 2", outputVariable = "res")
            )
        )
        val parentManifest = ToolManifest(
            id = "parent_chain",
            name = "Double + 10",
            description = "Chaîne vers Doubleur",
            fields = listOf(UiFieldSpec(id = "n", label = "N", type = UiFieldType.NUMBER)),
            outputs = listOf(UiOutputSpec(id = "out", label = "Final", type = UiOutputType.CALCULATION_RESULT, sourceVariable = "final_val")),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "call",
                    type = WorkflowStepType.CALL_TOOL,
                    title = "Appeler Doubleur",
                    targetToolId = "child_double",
                    template = "{\"x\":\"{{n}}\"}",
                    outputVariable = "doubled"
                ),
                WorkflowStepSpec(
                    id = "add10",
                    type = WorkflowStepType.SANDBOX_EXPRESSION,
                    title = "Ajouter 10",
                    expression = "doubled + 10",
                    outputVariable = "final_val"
                )
            )
        )
        toolRepo.saveTool(childCalc, "Init").getOrThrow()
        toolRepo.saveTool(parentManifest, "Init").getOrThrow()

        val result = runtime.execute(parentManifest, mapOf("n" to "15"))
        assertTrue(result.success)
        assertEquals("40", result.outputs["out"])
    }
}
