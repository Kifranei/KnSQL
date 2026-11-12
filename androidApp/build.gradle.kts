import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ArchiveOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity)
    implementation("androidx.core:core-ktx:1.16.0")
}

/**
 * xerial sqlite-jdbc keeps its Android natives in a separate natives-android jar laid out as
 * org/sqlite/native/Linux-Android/<arch>/libsqlitejdbc.so. They are copied into jniLibs ABI folders so
 * Android installs them and the driver's System.loadLibrary("sqlitejdbc") fallback finds them.
 */
abstract class ExtractSqliteNatives @Inject constructor(
    private val files: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:InputFiles
    abstract val nativesJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val abis = mapOf("aarch64" to "arm64-v8a", "arm" to "armeabi-v7a", "x86" to "x86", "x86_64" to "x86_64")
        files.delete { delete(outputDir) }
        abis.forEach { (arch, abi) ->
            files.copy {
                from(archives.zipTree(nativesJar.singleFile)) {
                    include("org/sqlite/native/Linux-Android/$arch/libsqlitejdbc.so")
                    eachFile { path = name }
                    includeEmptyDirs = false
                }
                into(outputDir.dir(abi))
            }
        }
    }
}

val sqliteNatives = configurations.detachedConfiguration(
    dependencies.create(libs.sqlite.get().toString() + ":natives-android"),
).apply { isTransitive = false }

val extractSqliteNatives = tasks.register<ExtractSqliteNatives>("extractSqliteNatives") {
    nativesJar.from(sqliteNatives)
    outputDir.set(layout.buildDirectory.dir("generated/sqliteJni"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractSqliteNatives, ExtractSqliteNatives::outputDir)
    }
}

android {
    namespace = "dev.knsql"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.knsql"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use(::load)
    }
    fun signingSecret(name: String): String? =
        localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull ?: System.getenv(name)
    // Release signing is optional so a fresh clone can build: without release.jks and its passwords
    // (local.properties, -P or environment variables) the release APK is simply left unsigned.
    val keystore = rootProject.file("release.jks")
    val storePassword = signingSecret("KNSQL_STORE_PASSWORD")
    val keyPassword = signingSecret("KNSQL_KEY_PASSWORD")
    val canSign = keystore.exists() && storePassword != null && keyPassword != null
    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = keystore
                this.storePassword = storePassword
                keyAlias = signingSecret("KNSQL_KEY_ALIAS") ?: "release"
                this.keyPassword = keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            pickFirsts += "META-INF/services/java.sql.Driver"
            // ojdbc 23, ojdbc 19 and the DM driver each ship their licence here; keep all three.
            merges += "META-INF/license.txt"
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // Desktop natives of sqlite-jdbc; Android loads the jniLibs copy instead.
                "org/sqlite/native/**",
            )
        }
    }
}
