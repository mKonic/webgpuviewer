// Resolves androidx.webgpu from a clone of mpreg-ca/androidx-webgpu-repo instead of
// raw.githubusercontent.com, which refuses under load ("Backend.max_conn reached"):
//
//   git clone --depth 1 https://github.com/mpreg-ca/androidx-webgpu-repo.git <clone>
//   ANDROIDX_WEBGPU_REPO=<clone> ./gradlew --init-script scripts/androidx-webgpu-local.init.gradle.kts ...
val clone = System.getenv("ANDROIDX_WEBGPU_REPO")
    ?: error("ANDROIDX_WEBGPU_REPO must point at a clone of mpreg-ca/androidx-webgpu-repo")

settingsEvaluated {
    dependencyResolutionManagement {
        repositories {
            exclusiveContent {
                forRepository {
                    maven { url = uri(clone) }
                }
                filter { includeGroup("androidx.webgpu") }
            }
        }
    }
}
