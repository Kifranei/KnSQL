import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")
    sourceSets {
        named("desktopMain") {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.knsql.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // suggestRuntimeModules only sees static references. The JDBC drivers also reach for JNDI,
            // Kerberos, JMX and XML reflectively, SQL Server TLS needs EC crypto, and GBK script files
            // need the extra charsets.
            modules(
                "java.instrument", "java.prefs", "java.sql", "jdk.unsupported",
                "java.naming", "java.security.jgss", "java.management", "java.xml",
                "jdk.crypto.ec", "jdk.charsets", "jdk.localedata",
            )
            packageName = "KnSQL"
            packageVersion = "1.1.0"
            description = "Miuix database workbench"
            vendor = "KnSQL"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                // Keep the same code across versions so a newer MSI upgrades the installed one.
                upgradeUuid = "6a0f3c1e-7b5d-4d2a-9c8e-2f4b1a6d9e30"
            }
        }
    }
}
