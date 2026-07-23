# Project-specific release shrinker rules.
# Keep this file present because app/build.gradle.kts includes it for release builds.
# Add narrowly scoped keep rules here only when a dependency or reflection path needs them.

# Tink (pulled in transitively) references Google's errorprone annotations, which are
# compile-only and absent at runtime by design — suppress, don't bundle them.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
