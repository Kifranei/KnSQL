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
| Oracle JDBC (ojdbc11) | Oracle Database 19c – 23ai driver | Oracle Free Use Terms and Conditions (FUTC) | https://www.oracle.com/downloads/licenses/oracle-free-license.html | Gradle artifact: `com.oracle.database.jdbc:ojdbc11`. At build time KnSQL rewrites a few driver classes so they load on Android: JMX / MBean registration and connection lifecycle listeners are removed, and regular expressions Android cannot compile (`[A-z`, possessive quantifiers, `(?U)`) are rewritten. See `shared/oracle-patch/`. The rewritten driver is bundled in the Android APK. |
| Oracle JDBC (ojdbc8 19.x, relocated) | Oracle Database 11g R2 – 18c driver | Oracle Free Use Terms and Conditions (FUTC) | https://www.oracle.com/downloads/licenses/oracle-free-license.html | Gradle artifact: `com.oracle.database.jdbc:ojdbc8` (19.33.0.0). At build time every class and resource is moved from `oracle.*` to `dev.knsql.ojdbc19.oracle.*` so it can sit next to ojdbc11, and the same Android patches as ojdbc11 are applied (`shared/oracle-patch/`). Bundled in the Android APK and the desktop app. |
| Dameng JDBC (DmJdbcDriver8) | Dameng DM7 / DM8 driver | Dameng JDBC Using License (binary redistribution permitted with the notice reproduced; the Maven POM declares Apache-2.0) | https://central.sonatype.com/artifact/com.dameng/DmJdbcDriver8 · https://gitee.com/dmedu/dm-jdbc-jars | Gradle artifact: `com.dameng:DmJdbcDriver8` (8.1.5.45), used unmodified. The licence text is reproduced below. |
| MariaDB Connector/J | MySQL and MariaDB driver | LGPL-2.1 | https://github.com/mariadb-corporation/mariadb-connector-j | Gradle artifact: `org.mariadb.jdbc:mariadb-java-client`, used unmodified |
| PostgreSQL JDBC Driver (pgjdbc) | PostgreSQL driver | BSD-2-Clause | https://github.com/pgjdbc/pgjdbc | Gradle artifact: `org.postgresql:postgresql` (42.7.14), used unmodified |
| SQLite JDBC (xerial) | SQLite driver with the bundled SQLite engine | Apache-2.0 (the bundled SQLite library is public domain) | https://github.com/xerial/sqlite-jdbc · https://www.sqlite.org/copyright.html | Gradle artifacts: `org.xerial:sqlite-jdbc` (3.54.1.0) and, for Android, its `natives-android` classifier. Used unmodified; on Android the native `libsqlitejdbc.so` files are copied from `natives-android` into the APK's `lib/<abi>/` and the desktop natives are left out of the APK. |
| Microsoft JDBC Driver for SQL Server | SQL Server driver | MIT | https://github.com/microsoft/mssql-jdbc | Gradle artifact: `com.microsoft.sqlserver:mssql-jdbc` |
| jTDS | Fallback SQL Server driver | LGPL-2.1 | https://sourceforge.net/projects/jtds/ | Gradle artifact: `net.sourceforge.jtds:jtds`, used unmodified |
| ASM | Bytecode rewriting and relocation of the Oracle drivers during the build | BSD-3-Clause | https://asm.ow2.io/ | Build-time only, not bundled in the app |

## Ported source files

| Source | Files in KnSQL | License | Link | Notes |
| :-- | :-- | :-- | :-- | :-- |
| Miuix `miuix-glass` — OS4 glass navigation bar (via Halcyon) | `shared/src/androidMain/kotlin/top/yukonga/miuix/kmp/glass/*` | Apache-2.0 | https://github.com/compose-miuix-ui/miuix · https://github.com/Kifranei/Halcyon | `GlassNavigationBar`, `glassPanel` and the glass styles / shaders they need. `miuix-glass` is not published to Maven Central yet, so the sources are vendored as carried in Halcyon; original headers kept. Local change: `GlassNavigationItem.badge`. |
| Halcyon — About page | `shared/src/commonMain/kotlin/dev/knsql/ui/AboutPage.kt`, `AboutVisualTokens.kt` | Apache-2.0 | https://github.com/Kifranei/Halcyon | Flowing background, title blend and frosted-card layout |
| Miuix example — flowing background | `shared/src/commonMain/kotlin/dev/knsql/ui/effect/*` | Apache-2.0 | https://github.com/compose-miuix-ui/miuix | `BgEffectBackground`, `BgEffectPainter`, OS2 / OS3 shaders, reached through Halcyon |

## Implementation references

| Project | Reference | License | Link |
| :-- | :-- | :-- | :-- |
| KernelSU | Landscape safe-area handling of the About page | GPL-3.0 | https://github.com/tiann/KernelSU |

No KernelSU source code is bundled; it is listed as a behaviour reference only.

## Note on redistribution

The liquid-glass bottom bar used to be ported from Halcyon files licensed GPL-3.0-only. It has been replaced by the Apache-2.0 Miuix `miuix-glass` navigation bar listed above, so no GPL-3.0-only source is compiled into the build any more. For the Oracle, MariaDB, jTDS and Dameng drivers, redistribution must follow the respective upstream terms.

## Dameng JDBC Using License

The Dameng JDBC driver ships with the following notice (`META-INF/license.txt` in `DmJdbcDriver8`), reproduced here as its terms require:

```
Dameng JDBC Using License
------------------------------------------------------------------------------------------------
Ownership

DAMENG(DM) or its licensors retain all ownership and intellectual property rights to the Programs.

-------------------------------------------------------------------------------------------------
Disclaimer of Warranties; Limitation of Liability

Redistribution and use in binary forms, with or without modification, are permitted provided that following conditions are met:
1. Redistribution in binary form must reproduce the above copyright notice, the list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.


THE PROGRAMS ARE PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND. DAMENG(DM) FURTHER DISCLAIMS ALL WARRANTIES,
EXPRESS AND IMPLIED, INCLUDING WITHOUT LIMITATION, ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
A PARTICULAR PURPOSE, OR NONINFRINGEMENT.

IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW WILL DAMENG(DM) BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL,
SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING
BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES
OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN
ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

Last updated: 10 September 2025
```
