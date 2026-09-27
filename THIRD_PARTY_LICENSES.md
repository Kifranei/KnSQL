# Third-Party Licenses

KnSQL main project is licensed under Apache-2.0. Third-party components keep their own licenses.

This document lists the third-party libraries, database drivers, ported source files and implementation references used by KnSQL.

## Libraries and drivers

| Project | Purpose | License | Link | Notes |
| :-- | :-- | :-- | :-- | :-- |
| Miuix | MIUI / HyperOS-style Compose Multiplatform UI components, icons, preference widgets, blur, progressive blur and backdrop effects | Apache-2.0 | https://github.com/compose-miuix-ui/miuix | Gradle artifacts: `top.yukonga.miuix.kmp:miuix-ui`, `miuix-preference`, `miuix-icons`, `miuix-blur` (0.9.4) |
| Compose Multiplatform / Jetpack Compose | UI toolkit for Android and desktop | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform | Gradle plugin `org.jetbrains.compose` |
| AndroidX Activity | Activity integration, file pickers, permission requests | Apache-2.0 | https://developer.android.com/jetpack/androidx | Gradle artifact: `androidx.activity:activity-compose` |
| Kotlin / Kotlinx Coroutines / Kotlinx Serialization | Language runtime, concurrency and settings persistence | Apache-2.0 | https://github.com/JetBrains/kotlin | `kotlinx-coroutines-*`, `kotlinx-serialization-json` |
| Oracle JDBC (ojdbc11) | Oracle Database driver | Oracle Free Use Terms and Conditions (FUTC) | https://www.oracle.com/downloads/licenses/oracle-free-license.html | Gradle artifact: `com.oracle.database.jdbc:ojdbc11`. At build time KnSQL rewrites a few driver classes so they load on Android: JMX / MBean registration and connection lifecycle listeners are removed, and regular expressions Android cannot compile (`[A-z`, possessive quantifiers, `(?U)`) are rewritten. See `shared/oracle-patch/`. The rewritten driver is bundled in the Android APK. |
| MariaDB Connector/J | MySQL / MariaDB driver | LGPL-2.1 | https://github.com/mariadb-corporation/mariadb-connector-j | Gradle artifact: `org.mariadb.jdbc:mariadb-java-client`, used unmodified |
| Microsoft JDBC Driver for SQL Server | SQL Server driver | MIT | https://github.com/microsoft/mssql-jdbc | Gradle artifact: `com.microsoft.sqlserver:mssql-jdbc` |
| jTDS | Fallback SQL Server driver | LGPL-2.1 | https://sourceforge.net/projects/jtds/ | Gradle artifact: `net.sourceforge.jtds:jtds`, used unmodified |
| ASM | Bytecode rewriting of the Oracle driver during the build | BSD-3-Clause | https://asm.ow2.io/ | Build-time only, not bundled in the app |

## Ported source files

| Source | Files in KnSQL | License | Link | Notes |
| :-- | :-- | :-- | :-- | :-- |
| Halcyon — liquid-glass floating bottom bar | `shared/src/androidMain/kotlin/dev/knsql/ui/glass/FloatingBottomBar.kt`, `DampedDragAnimation.kt`, `InteractiveHighlight.kt`, `DragGestureInspector.kt` | **GPL-3.0-only** | https://github.com/Kifranei/Halcyon | These files carry `SPDX-License-Identifier: GPL-3.0-only` headers and credit InstallerX Revived and KernelSU contributors. They keep that license; see the note below. |
| Halcyon — liquid-glass helpers | `shared/src/androidMain/kotlin/dev/knsql/ui/glass/Lens.kt`, `InnerShadow.kt`, `CombinedBackdrop.kt`, `Vibrancy.kt` | Apache-2.0 | https://github.com/Kifranei/Halcyon | Original headers kept |
| Halcyon — About page | `shared/src/commonMain/kotlin/dev/knsql/ui/AboutPage.kt`, `AboutVisualTokens.kt` | Apache-2.0 | https://github.com/Kifranei/Halcyon | Flowing background, title blend and frosted-card layout |
| Miuix example — flowing background | `shared/src/commonMain/kotlin/dev/knsql/ui/effect/*` | Apache-2.0 | https://github.com/compose-miuix-ui/miuix | `BgEffectBackground`, `BgEffectPainter`, OS2 / OS3 shaders, reached through Halcyon |

## Implementation references

| Project | Reference | License | Link |
| :-- | :-- | :-- | :-- |
| KernelSU | Landscape safe-area handling of the About page | GPL-3.0 | https://github.com/tiann/KernelSU |

No KernelSU source code is bundled; it is listed as a behaviour reference only.

## Note on the GPL-3.0-only files

The four GPL-3.0-only files above are compiled into the Android build. An APK that includes them is a combined work, and distributing it must also satisfy GPL-3.0: the complete corresponding source has to be available under GPL-3.0 terms. This repository publishes that source. The liquid-glass bottom bar is off by default, but the code is still part of the build.

If those files are replaced by an independent implementation, this note and the table above should be updated before release. For the Oracle, MariaDB and jTDS drivers, redistribution must follow the respective upstream terms.
