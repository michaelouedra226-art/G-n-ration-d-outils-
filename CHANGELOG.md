# Changelog

Toutes les modifications notables de **ForgeAI** sont documentées dans ce fichier.

## [1.2.0] - 2026-11-25

### Ajouté
- **Mode Autonome & Suites Multi-Plugins** : L'Architecte planifie automatiquement 1 outil ciblé ou une suite coordonnée de 2 à 4 plugins, avec test à blanc QA (`dryRunDeterministic`) et boucle d'auto-réparation (jusqu'à 2 tentatives).
- **16 briques d'exécution sandboxées** : ajout de `CONDITION_BRANCH`, `TEXT_TRANSFORM` (SHA-256, Base64, JSON extract/pretty, CSV→table, regex, UUID, générateur de mots de passe), `DATE_TIME`, `LIST_STORAGE_APPEND`/`READ` (suivi cumulatif avec `COUNT`/`SUM`), `HTTP_REQUEST` HTTPS protégé anti-SSRF (`NETWORK_FETCH`), `SPEAK_TEXT` via Android `TextToSpeech` (`TEXT_TO_SPEECH`) et composition inter-outils `CALL_TOOL` (protection anti-cycles).
- **Nouvelles vues de sortie natives** : `TableOutputView`, `BarChartOutputView` (`Canvas`), `ListItemsOutputView` et bouton de lecture vocale TTS.

## [1.1.0] - 2026-11-25

### Ajouté
- Support complet du chat multi-tours (`conversationHistory`) dans `AiGateway`, `SandboxedToolRuntime`, `ToolsViewModel` et `ChatTranscriptOutputView`.
- Respect de l'en-tête HTTP `Retry-After` lors des erreurs 429 dans `OkHttpAiGateway` et `ApiRouterService`.
- Découverte en direct des modèles (`GET /models`) dans le Gestionnaire API avec dialogue de filtrage et application rapide.
- Amélioration itérative de plugins existants par les agents IA (`refineTool` avec incrément SemVer automatique) ainsi qu'import/export JSON de manifestes.
- Rendu Markdown enrichi dans les PDF générés (`#`/`##`/`###`, listes, blocs de code) et aperçu natif page par page via `PdfRenderer` (`PdfPreviewCard`), plus rendu structuré `KeyValueCardsOutputView`.
- Activation de R8 (`isMinifyEnabled` + `isShrinkResources`) pour les builds Release et ajout de tests unitaires dédiés (`OkHttpAiGatewayTest`, tests étendus sur `ApiRouterServiceTest` et `SandboxedToolRuntimeTest`).

## [1.0.0] - 2026-11-24

### Ajouté
- Architecture modulaire Kotlin / Jetpack Compose / Material 3 (Clean Architecture, MVVM, Repository, DI manuel).
- Pipeline collaboratif multi-agents (8 rôles : Architecte, UX Designer, Développeur, Expert IA, Testeur QA, Correcteur Sécurité, Optimiseur, Documentation).
- Runtime déclaratif sandboxé (`ToolManifest` + `ManifestValidator` + `SandboxGuard` + `SafeExpressionEvaluator`).
- Générateur PDF A4 natif (`AndroidPdfGeneratorService`) avec export SAF et partage `FileProvider`.
- Gestionnaire d'API avancé multi-fournisseurs et multi-clés chiffrées Android Keystore AES-256-GCM (rotation automatique, équilibrage de charge, gestion de quota 429, statistiques et tests en direct).
- Historique complet par outil (version SemVer, auteur, date, statut, journal des modifications, traces d'exécution).
- Thèmes Sombre / Clair / Système avec typographie locale (`Space Grotesk`, `Inter`, `JetBrains Mono`).
- Suite de tests unitaires et Robolectric + workflow GitHub Actions Release APK.
