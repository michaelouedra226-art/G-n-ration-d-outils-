package com.example.core.security

import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import java.net.URI

/**
 * Enforces strict security isolation between dynamic plugins and the ForgeAI host runtime.
 *
 * Prevents unauthorized capabilities, oversized payloads, path traversal, unsafe URL schemes,
 * and unbounded workflow execution loops.
 */
object SandboxGuard {

    const val MAX_WORKFLOW_STEPS = 25
    const val MAX_INPUT_TEXT_LENGTH = 100_000
    const val MAX_STORAGE_KEY_LENGTH = 128
    const val MAX_STORAGE_VALUE_LENGTH = 64_000
    const val DEFAULT_EXECUTION_TIMEOUT_MS = 60_000L

    private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]{1,64}$")
    private val DANGEROUS_TEXT_PATTERNS = listOf(
        Regex("(?i)<script\\b"),
        Regex("(?i)javascript:"),
        Regex("(?i)file:///"),
        Regex("\\.\\./")
    )

    /**
     * Verifies that a plugin manifest declares [requiredPermission] before executing a privileged step.
     *
     * @throws SecurityException if the tool lacks the required permission.
     */
    fun requirePermission(manifest: ToolManifest, requiredPermission: ToolPermission) {
        if (requiredPermission !in manifest.permissions) {
            throw SecurityException(
                "Permission refusée dans le sandbox : l'outil '${manifest.name}' ne déclare pas ${requiredPermission.label}."
            )
        }
    }

    /**
     * Validates a user-supplied input string against sandbox size bounds.
     */
    fun sanitizeInput(value: String, fieldLabel: String): String {
        require(value.length <= MAX_INPUT_TEXT_LENGTH) {
            "Le champ '$fieldLabel' dépasse la taille maximale autorisée ($MAX_INPUT_TEXT_LENGTH caractères)."
        }
        return value
    }

    /**
     * Validates a scoped storage key to prevent traversal or separator injection.
     */
    fun validateStorageKey(key: String): String {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_STORAGE_KEY_LENGTH) {
            "Clé de stockage invalide."
        }
        require(SAFE_ID_REGEX.matches(trimmed)) {
            "La clé de stockage '$trimmed' contient des caractères interdits."
        }
        return trimmed
    }

    /**
     * Validates that a custom API base URL uses HTTPS and targets a valid public host.
     */
    fun validateHttpsEndpoint(url: String): Result<String> = runCatching {
        val trimmed = url.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "L'URL du fournisseur ne peut pas être vide." }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Seules les connexions chiffrées HTTPS sont autorisées."
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Nom d'hôte manquant dans l'URL.")
        require(
            host != "localhost" &&
                host != "127.0.0.1" &&
                host != "0.0.0.0" &&
                host != "169.254.169.254" &&
                !host.startsWith("10.") &&
                !host.startsWith("192.168.")
        ) {
            "Hôte réseau privé ou local interdit ($host)."
        }
        trimmed
    }

    /**
     * Checks that a manifest template string does not contain unsafe executable patterns.
     */
    fun assertSafeTemplate(template: String) {
        for (pattern in DANGEROUS_TEXT_PATTERNS) {
            if (pattern.containsMatchIn(template)) {
                throw SecurityException("Motif potentiellement dangereux détecté dans le workflow.")
            }
        }
    }
}
