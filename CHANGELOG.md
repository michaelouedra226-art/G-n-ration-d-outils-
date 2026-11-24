# Changelog

Toutes les modifications notables de **ForgeAI** sont documentées dans ce fichier.

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
