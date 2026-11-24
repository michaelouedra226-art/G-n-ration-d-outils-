package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.ai.AiGateway
import com.example.core.ai.AiRequest
import com.example.core.ai.AiResponse
import com.example.core.ai.ApiRouterService
import com.example.core.ai.ConnectionTestResult
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
@Config(sdk = [36])
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
}
