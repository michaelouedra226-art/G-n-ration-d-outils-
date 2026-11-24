package com.example

import com.example.core.security.SandboxGuard
import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.UiFieldSpec
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.UiOutputSpec
import com.example.domain.manifest.UiOutputType
import com.example.domain.manifest.WorkflowStepSpec
import com.example.domain.manifest.WorkflowStepType
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxGuardTest {

    @Test
    fun `rejects ai workflow step without AI_INFERENCE permission`() {
        val manifest = ToolManifest(
            id = "tool_1",
            name = "Test AI",
            description = "Description valide",
            permissions = emptyList(),
            fields = listOf(UiFieldSpec(id = "text", label = "Texte", type = UiFieldType.TEXT)),
            outputs = listOf(UiOutputSpec(id = "out", label = "Sortie", type = UiOutputType.TEXT_OUTPUT)),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "s1",
                    type = WorkflowStepType.AI_PROMPT,
                    title = "Prompt",
                    template = "{{text}}"
                )
            )
        )
        assertTrue(ManifestValidator.validate(manifest).isFailure)
    }

    @Test
    fun `rejects script injection in template`() {
        val manifest = ToolManifest(
            id = "tool_2",
            name = "Unsafe",
            description = "Description valide",
            permissions = listOf(ToolPermission.CLIPBOARD),
            fields = listOf(UiFieldSpec(id = "text", label = "Texte")),
            outputs = listOf(UiOutputSpec(id = "out", label = "Sortie")),
            workflow = listOf(
                WorkflowStepSpec(
                    id = "s1",
                    type = WorkflowStepType.TEMPLATE_FORMAT,
                    title = "Format",
                    template = "<script>alert(1)</script>{{text}}"
                )
            )
        )
        assertTrue(ManifestValidator.validate(manifest).isFailure)
    }

    @Test
    fun `blocks non-https and loopback endpoints`() {
        assertTrue(SandboxGuard.validateHttpsEndpoint("http://api.openai.com/v1").isFailure)
        assertTrue(SandboxGuard.validateHttpsEndpoint("https://localhost/v1").isFailure)
        assertTrue(SandboxGuard.validateHttpsEndpoint("https://127.0.0.1/v1").isFailure)
        assertTrue(SandboxGuard.validateHttpsEndpoint("https://api.openai.com/v1").isSuccess)
    }
}
