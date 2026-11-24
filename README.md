# ForgeAI

Plateforme Android native (Kotlin + Jetpack Compose + Material 3) de création, gestion et exécution sandboxée de plugins intelligents à partir d'instructions en langage naturel.

## Principes fondamentaux

- **Démarrage 100 % vierge** : aucune donnée de démonstration, aucun outil fictif, aucune clé API pré-remplie. Toutes les données proviennent exclusivement des actions de l'utilisateur.
- **Runtime déclaratif sandboxé** : aucune compilation à chaud risquée. Chaque outil s'exécute via un `ToolManifest` validé dans un environnement cloisonné (limites d'étapes, timeout configurable, permissions explicites, évaluateur d'expressions sans réflexion, stockage SQLite isolé par `toolId`).
- **Pipeline multi-agents (8 rôles)** : *Architecte Logiciel*, *UX Designer*, *Développeur Plugin*, *Expert IA*, *Testeur QA*, *Correcteur Sécurité*, *Optimiseur* et *Agent Documentation* collaborent pour produire et auditer chaque manifeste.
- **Gestionnaire d'API multi-fournisseurs** : ajout illimité de fournisseurs (`OpenAI`, `Anthropic`, `DeepSeek`, `OpenRouter`, `Agnes AI`, `Compatible OpenAI`, emplacement `Gemini / Firebase AI Logic`) et de clés chiffrées via **Android Keystore AES-256-GCM**, avec rotation automatique sur quota (HTTP 429) ou erreur d'authentification, équilibrage de charge (Priorité, Round-Robin, Fiabilité, Latence minimale), test de connexion en direct et statistiques par clé.

---

## Architecture modulaire (Clean Architecture + MVVM + Repository)

```text
app/src/main/java/com/example/
├── ForgeApplication.kt                 # Point d'entrée et conteneur DI manuel
├── MainActivity.kt                     # Edge-to-edge, NavigationBar/NavigationRail adaptatifs
├── core/
│   ├── ai/
│   │   ├── AiModels.kt                 # Fournisseurs, stratégies de routage, erreurs typées
│   │   ├── AiGateway.kt                # Gateway unifiée (OkHttp OpenAI/Anthropic + notice Gemini)
│   │   ├── ApiRouterService.kt         # Équilibrage de charge, rotation, cooldown 429, tests
│   │   ├── MultiAgentForgePipeline.kt  # Orchestrateur collaboratif à 8 agents
│   │   └── NetworkMonitor.kt           # Vérification connectivité ConnectivityManager
│   ├── data/
│   │   ├── local/                      # Entités, DAOs et base Room (SQLite)
│   │   ├── preferences/                # DataStore (thème, stratégie routage, timeout, auteur)
│   │   └── repository/                 # ToolRepository & ApiManagerRepository
│   ├── di/
│   │   └── ForgeContainer.kt           # Injection de dépendances modulaire
│   └── security/
│       ├── CryptoManager.kt            # Chiffrement Android Keystore AES-256-GCM + masquage
│       ├── SafeExpressionEvaluator.kt  # Évaluateur mathématique/logique sans réflexion
│       └── SandboxGuard.kt             # Validation permissions, bornes, URLs HTTPS, anti-XSS
├── domain/
│   └── manifest/
│       ├── ToolManifest.kt             # Schéma déclaratif complet des plugins (Moshi)
│       └── ManifestValidator.kt        # Vérification structurelle et sécurité des manifestes
├── runtime/
│   ├── PdfGeneratorService.kt          # Générateur PDF A4 natif (android.graphics.pdf.PdfDocument)
│   └── SandboxedToolRuntime.kt         # Exécuteur sandboxé des workflows de plugins
└── ui/
    ├── components/                     # Composants Material 3 réutilisables & rendu Markdown
    ├── screens/
    │   ├── atelier/                    # Forge en langage naturel + Constructeur manuel
    │   ├── tools/                      # Catalogue vide par défaut, Runner dynamique, Détails/Changelog
    │   ├── apimanager/                 # Gestionnaire illimité de fournisseurs & clés chiffrées
    │   └── settings/                   # Thème, auteur par défaut, politique sandbox
    └── theme/                          # Palette Obsidian/Alabaster & typographie locale
```

---

## Sécurité & Conformité

- **Chiffrement au repos** : les clés API ne sont jamais stockées en clair ni journalisées ; elles sont chiffrées via `AndroidKeyStore` (`AES/GCM/NoPadding`, 256 bits).
- **Moindre privilège** : aucune permission de stockage large (`READ_EXTERNAL_STORAGE` / `READ_MEDIA_*`). La sélection de documents et d'images utilise exclusivement **Storage Access Framework** et **Android Photo Picker**.
- **Confinement réseau** : `usesCleartextTraffic="false"` et validation stricte `https://` bloquant les hôtes privés/locaux.

---

## Compiler et tester localement

### Prérequis
- JDK 17+
- Android SDK (`compileSdk 36`, `minSdk 24`)
- Gradle 9.3.1

### Commandes

```bash
# Lancer les tests unitaires et Robolectric
gradle :app:testDebugUnitTest

# Compiler l'APK Debug
gradle :app:assembleDebug

# Compiler l'APK Release (avec variables de signature KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD)
gradle :app:assembleRelease
```

---

## GitHub Actions (CI/CD)

Le workflow `.github/workflows/android-release.yml` s'exécute automatiquement sur chaque `push` vers `main` ou `master` :
1. Exécute `gradle :app:testDebugUnitTest`.
2. Compile l'APK Release (`gradle :app:assembleRelease`).
3. Publie l'APK dans les **Artifacts** GitHub Actions (`forgeai-release-apk`).

Pour signer avec votre propre keystore de production, définissez les secrets GitHub suivants :
- `UPLOAD_KEYSTORE_BASE64`
- `STORE_PASSWORD`
- `KEY_PASSWORD`
