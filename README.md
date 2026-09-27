<!--suppress ALL -->

<h1 align="center">KnSQL</h1>

<p align="center">
  <b>一款贴近 MIUI / HyperOS 体验、为平板运维准备的数据库工作台</b>
</p>

<p align="center">
  <a href="https://github.com/Kifranei/KnSQL/releases"><img src="https://img.shields.io/github/v/release/Kifranei/KnSQL?style=flat&color=6750A4" alt="Version"></a>
  <a href="https://github.com/Kifranei/KnSQL/releases"><img src="https://img.shields.io/github/downloads/Kifranei/KnSQL/total?style=flat&color=orange" alt="Downloads"></a>
  <a href="https://github.com/Kifranei/KnSQL/commits"><img src="https://img.shields.io/github/last-commit/Kifranei/KnSQL?style=flat" alt="Last Commit"></a>
  <a href="https://github.com/Kifranei/KnSQL/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Kifranei/KnSQL?style=flat" alt="License"></a>
</p>

<p align="center">
  <b>Oracle 19c · MySQL · SQL Server · 整脚本执行 · 对象树 · DDL 导出 · 表数据与结构编辑 · 多语言界面</b>
</p>

---

## ✨ 项目简介

**KnSQL** 是一款基于 **Compose Multiplatform 与 Miuix** 构建的数据库工具，同一套界面运行在 Android 平板、手机和桌面 JVM 上。

界面由连接、对象树、SQL 编辑器和结果网格组成，面向在平板上做运维巡检的场景：支持通过 Tailscale 等 VPN 访问内网数据库，后台保持连接，写入前确认，所有修改先预览再执行。

---

## 🚀 功能特性

### 🔌 连接

- 支持 **Oracle 19c**（服务名或 SID，自动互相回退；可选 SYSDBA）、**MySQL / MariaDB**、**SQL Server**（Microsoft JDBC，加载失败时回退 jTDS）。
- 数据库流量绑定到系统 VPN，Tailscale 内网转发可直接连通；连不上时自动重试一次并提示检查 VPN。
- 后台保活双层保险：空闲会话每 90 秒心跳一次，断线后在下次执行前自动重连；Android 上以前台服务和常驻通知防止进程被系统回收。
- 同一个库只保留一个会话，多个库之间用顶部胶囊切换，每个会话的对象树分开显示。
- 密码使用设备上生成的 AES-GCM 密钥加密，保存在应用私有目录。

### 📝 SQL 编辑与执行

- **Alt+X** 执行整个脚本，**Ctrl+Enter** 执行光标所在语句。
- 脚本分隔：`;`、Oracle 单独一行的 `/`、SQL Server 的 `GO`、MySQL 的 `DELIMITER`，PL/SQL 与 T-SQL 过程体内的分号不会被拆开。
- 可打开整个 `.sql` 文件（UTF-8 / GBK，最大 20 MB），也可把编辑器内容保存成文件。
- INSERT、UPDATE、DELETE 和 DDL 默认先弹出确认。
- 执行历史保留最近 100 条：点一条放回编辑器，长按多选删除，可一键清空。

### 📊 结果网格

- 列宽按表头、注释和数据测量，表头与每一行对齐；行按需加载，不限制行数时也能流畅滚动。
- 单表查询可勾选显示列注释。
- 复制为制表符分隔文本（可直接粘贴到表格），或导出 CSV（UTF-8 BOM，Excel 打开不乱码）。
- 可设置单次最多行数，也可打开「不限制行数」。

### 🗂 对象树与 DDL

- 按库 / Schema / 表 / 视图 / 列浏览，显示表、视图和列的注释。
- 长按表查看 DDL，长按 Schema 或数据库导出全部 DDL，按可重建顺序输出：序列、类型、表、索引、注释、外键、视图、函数、存储过程、包、触发器、同义词。
- Oracle 使用 `DBMS_METADATA`，MySQL 使用 `SHOW CREATE`，SQL Server 从系统表生成；可复制、保存为 `.sql` 或放入编辑器。

### ✏️ 表数据与结构编辑

- **编辑数据**：点单元格修改、设为 NULL，新增行（未填写的列使用默认值），长按选择行后删除。
- 所有修改先预览生成的 SQL，再在一个事务中提交；任一条失败，或 UPDATE / DELETE 影响的行数不是 1，都会全部回滚。
- 行定位使用 Oracle 的 `ROWID` 或主键，值按列类型绑定参数执行；没有主键的表只能新增行。
- **修改表结构**：新增列、修改列（名称、类型、可空、默认值、注释）、删除列、修改表注释，执行前显示各数据库对应的 DDL。
- 只读连接不能进入编辑。

### 🎨 界面

- Miuix 0.9.4 组件，平板横屏为可展开 / 收起的侧边导航加三栏工作台，手机为底部导航加「对象 / SQL / 结果」分页。
- 顶栏渐进模糊；底栏默认高斯模糊，可切换为液态玻璃悬浮底栏。
- 关于页流光背景与 Logo 混色，参考 Halcyon。
- 简体中文、繁體中文、English，可在设置中切换，并支持 Android 13 的应用语言设置。
- 跟随系统 / 浅色 / 深色外观，状态栏与导航条沉浸。

---

## 📱 运行要求

| 项目 | 要求 |
|:--|:--|
| Android 版本 | Android 13 / API 33 或更高版本 |
| Target SDK | Android 16 / API 36 |
| 桌面 | JDK 21，Windows / macOS / Linux |
| 网络 | 能访问数据库所在网络；内网数据库可通过 Tailscale 等 VPN |
| 通知权限 | Android 13 及以上用于后台保持连接的常驻通知 |

---

## 📦 下载

请从 [Releases](https://github.com/Kifranei/KnSQL/releases) 下载最新版本。

首次使用建议流程：

1. 安装 KnSQL，第一次连接时允许通知权限，用于后台保持连接。
2. 在「连接」页点右上角的 + 新建连接，先点「测试」确认能连上。
3. 数据库在内网时，先连接 Tailscale 或其他 VPN。
4. 在生产库上修改数据或表结构前，建议先在测试表上确认一遍。

---

## 🛠 构建

```bash
git clone https://github.com/Kifranei/KnSQL.git
cd KnSQL
./gradlew :androidApp:assembleDebug
```

Windows PowerShell：

```powershell
git clone https://github.com/Kifranei/KnSQL.git
cd KnSQL
.\gradlew.bat :androidApp:assembleDebug
```

需要 JDK 21 与 Android SDK（`ANDROID_HOME` 环境变量，或 `local.properties` 中的 `sdk.dir`；用 Android Studio 打开项目会自动生成）。

桌面端运行与打包：

```bash
./gradlew :desktopApp:run
./gradlew :desktopApp:packageMsi
```

Release 构建会依次从 `local.properties`、Gradle 属性（`-P`）和环境变量读取签名信息：

```bash
KNSQL_STORE_PASSWORD
KNSQL_KEY_PASSWORD
KNSQL_KEY_ALIAS   # 可选，默认 release
```

密钥库固定为项目根目录下的 `release.jks`。缺少密钥库或密码时，Release APK 不签名输出。`release.jks` 和 `local.properties` 已在 `.gitignore` 中排除，不要提交。

构建时会用 ASM 改写 Oracle JDBC 驱动中 Android 无法加载的部分（JMX 注册、Android 不支持的正则写法），见 `shared/oracle-patch/`。

测试：

```bash
./gradlew :shared:desktopTest
```

测试包含 SQL 脚本拆分、结果导出、DDL / DML 生成，以及三种语言翻译完整性的检查。发版后请同步推送 GitHub 与 GitLab 远端。

---

## 🧱 开源与许可

KnSQL 主项目以 **Apache-2.0** 协议开源。第三方组件保留其各自许可证，详见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

液态玻璃底栏中移植自 Halcyon 的 4 个文件为 **GPL-3.0-only**，包含它们的 APK 在分发时同时受 GPL-3.0 约束，说明见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md#note-on-the-gpl-30-only-files)。

---

## 👥 致谢

- **Halcyon** — 关于页流光背景、液态玻璃底栏与设置页卡片样式参考及移植来源。
- **KernelSU** — 关于页横屏避让参考。
- 感谢 KnSQL 所使用的 Miuix、Compose Multiplatform、Kotlin、Oracle JDBC、MariaDB Connector/J、Microsoft JDBC Driver for SQL Server、jTDS 以及其它开源项目。

---

## 🔗 仓库

- GitHub：https://github.com/Kifranei/KnSQL
- GitLab：https://gitlab.com/Kifranei/KnSQL
