plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val oraclePatchAsm: Configuration = configurations.create("oraclePatchAsm")

dependencies {
    oraclePatchAsm("org.ow2.asm:asm:9.8")
}

val patchedOracleJar = layout.buildDirectory.file("oracle/ojdbc-android.jar")

val compileOraclePatcher = tasks.register<JavaCompile>("compileOraclePatcher") {
    classpath = oraclePatchAsm
    source(layout.projectDirectory.dir("oracle-patch"))
    destinationDirectory.set(layout.buildDirectory.dir("oracle-patch/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

val patchOracleJdbc = tasks.register<JavaExec>("patchOracleJdbc") {
    dependsOn(compileOraclePatcher)
    classpath(compileOraclePatcher.get().destinationDirectory, oraclePatchAsm)
    mainClass.set("PatchOracleJdbc")
    val oracleJar = configurations.detachedConfiguration(dependencies.create(libs.oracle.get().toString()))
        .resolve()
        .single { it.name.startsWith("ojdbc") }
    inputs.file(oracleJar)
    outputs.file(patchedOracleJar)
    args(oracleJar.absolutePath, patchedOracleJar.get().asFile.absolutePath)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.knsql.shared"
        compileSdk = 37
        minSdk = 33
    }

    jvm("desktop")

    sourceSets {
        androidMain {
            kotlin.srcDir("src/jdbcMain/kotlin")
        }
        named("desktopMain") {
            kotlin.srcDir("src/jdbcMain/kotlin")
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.mariadb)
                implementation(libs.mssql)
                implementation(libs.oracle)
                implementation(libs.jtds)
            }
        }

        commonMain.dependencies {
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.blur)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.mariadb)
            implementation(libs.mssql)
            implementation(files(patchedOracleJar) { builtBy(patchOracleJdbc) })
            implementation(libs.jtds)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
