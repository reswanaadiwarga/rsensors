// Top-level build file. Plugin versions live in gradle/libs.versions.toml; they
// are declared here with `apply false` and applied per module.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
