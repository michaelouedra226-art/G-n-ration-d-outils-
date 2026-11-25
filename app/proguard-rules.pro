# Keep source file and line numbers for cleaner crash stack traces
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Kotlin Metadata (required for Moshi & Room Kotlin codegen/reflection)
-keep class kotlin.Metadata { *; }

# ForgeAI Domain Manifests, AI Models & Room Entities
-keep class com.example.domain.manifest.** { *; }
-keep class com.example.core.data.local.** { *; }
-keep class com.example.core.ai.** { *; }

# Moshi Codegen Adapters
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep class * extends com.squareup.moshi.JsonAdapter {
    public <init>(...);
}

# OkHttp / Okio optional platform dependencies
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
