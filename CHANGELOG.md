# Unreleased

中文更新日志
- Oracle 支持扩展到 11g R2、12c、18c：在 ojdbc 23 之外内置 ojdbc 19（重定位到 `dev.knsql.ojdbc19`），连接里可选「自动 / ojdbc 23 / ojdbc 19」；预览与表数据编辑改用 `ROWNUM`，11g 也能用。
- MySQL：支持 MySQL 8.x 默认的 `caching_sha2_password`（无 TLS 时自动获取公钥），新增「加密连接」开关。
- 新增达梦 DM7 / DM8：对象树、注释、DDL 导出、表数据与结构编辑，默认端口 5236。
- 新增 PostgreSQL（pgjdbc）：按 Schema 浏览（隐藏系统 Schema）、表 / 视图 / 物化视图 / 分区表、列注释、`LIMIT` 预览、按主键编辑数据、改列（类型 / 可空 / 默认值 / 重命名 / 注释），DDL 由 `pg_catalog` 生成（含约束、索引、触发器、序列、函数），默认端口 5432。
- 新增 MariaDB 连接类型（与 MySQL 共用 MariaDB Connector/J 和 `SHOW CREATE` DDL），默认端口 3306。
- 新增 SQLite（xerial sqlite-jdbc，自带 SQLite 3.54）：桌面直接打开 / 新建文件，Android 通过系统文件选择器复制到应用私有目录后打开；无主键的表按 `rowid` 编辑；只读会话以只读方式打开文件；可导出整个库的 DDL。
- SQL Server：支持命名实例（实例名字段）和「信任服务器证书」开关。
- Android 上的下拉选择（设置里的外观 / 语言、Oracle 驱动、数据库类型）和对象树长按菜单改用 Halcyon 的 OS4 液态玻璃弹窗；页面上的弹窗模糊背后的页面，连接编辑器里的弹窗用不透明填充。桌面端不变。
- 桌面端连接编辑器的数据库类型改为两行；Android 改为下拉选择；SQL 脚本识别 PostgreSQL 的 `$$` 美元引号，`DO` 块和函数体内的分号不再被拆开。


English Changelog
- Oracle now also covers 11g R2, 12c and 18c: ojdbc 19 ships next to ojdbc 23 (relocated to `dev.knsql.ojdbc19`), selectable per connection as Auto / ojdbc 23 / ojdbc 19. Previews and data editing use `ROWNUM`, so they work on 11g.
- MySQL: MySQL 8.x's default `caching_sha2_password` works without TLS (public key retrieval), and a new Encrypt connection switch enables TLS.
- New: Dameng DM7 / DM8 with the object tree, comments, DDL export and table data / structure editing; default port 5236.
- New: PostgreSQL (pgjdbc). Browse by schema (system schemas hidden), tables / views / materialized views / partitioned tables, column comments, `LIMIT` previews, data editing by primary key, column changes (type, NULL, default, rename, comment), and DDL built from `pg_catalog` with constraints, indexes, triggers, sequences and functions; default port 5432.
- New: a MariaDB connection type, sharing MariaDB Connector/J and the `SHOW CREATE` DDL with MySQL; default port 3306.
- New: SQLite (xerial sqlite-jdbc with SQLite 3.54 built in). Desktop opens or creates the file in place; Android copies the file picked in the system picker into app storage and opens the copy. Tables without a primary key are edited by `rowid`, read-only sessions open the file read-only, and the whole database's DDL can be exported.
- SQL Server: named instances (Instance field) and a Trust server certificate switch.
- Android dropdowns (Appearance and Language in Settings, the Oracle driver, the database type) and the object tree's long-press menu use Halcyon's OS4 liquid glass popups; popups on pages blur the page behind them, and those inside the connection editor use a solid fill. Desktop is unchanged.
- The database type picker in the connection editor wraps onto two rows on desktop and is a dropdown on Android, and the script splitter understands PostgreSQL `$$` dollar quoting, so semicolons inside `DO` blocks and function bodies stay put.

# 1.0.0

First release.

中文更新日志
- 连接 Oracle 19c（服务名 / SID 自动互相回退，可选 SYSDBA）、MySQL / MariaDB 与 SQL Server（Microsoft JDBC，失败时回退 jTDS）；Oracle 驱动在构建时改写，可在 Android 上加载。
- 数据库流量绑定系统 VPN，支持 Tailscale 内网转发；连不上时重新绑定网络并重试一次，失败时提示检查 VPN。
- 后台保活：空闲会话 90 秒心跳，断线后下次执行前自动重连；Android 前台服务 + 常驻通知，通知上可一键全部断开。
- SQL 编辑器：Alt+X 执行整个脚本、Ctrl+Enter 执行当前语句；支持 `;`、单独一行的 `/`、`GO` 与 `DELIMITER`；打开 / 保存 `.sql` 文件（UTF-8 / GBK，最大 20 MB）；编辑器支持全选。
- 写入与 DDL 执行前确认；执行历史保留 100 条，支持长按多选删除与清空。
- 结果网格：列宽测量对齐、按需加载、可显示列注释；复制为 TSV、导出 CSV（UTF-8 BOM）；支持不限制行数。
- 对象树：按会话分开显示，显示表、视图与列的注释；长按表查看 DDL，长按 Schema / 数据库导出全部 DDL（Oracle `DBMS_METADATA`、MySQL `SHOW CREATE`、SQL Server 系统表生成）。
- 编辑表数据：修改单元格、设为 NULL、新增行、删除行；先预览 SQL，再在单个事务中提交，行数不符即全部回滚；Oracle 按 `ROWID`、其他数据库按主键定位。
- 修改表结构：新增 / 修改 / 删除列、修改表注释，执行前显示各数据库对应的 DDL。
- Miuix 0.9.4 界面：平板可展开 / 收起的侧边导航与三栏工作台，手机底部导航与「对象 / SQL / 结果」分页；顶栏渐进模糊，底栏高斯模糊，可切换液态玻璃悬浮底栏。
- 关于页流光背景、Logo 混色与透光卡片，上滑渐变为纯色背景，横屏避让摄像头挖孔与导航条。
- 简体中文、繁體中文与 English，支持 Android 13 应用语言设置；跟随系统 / 浅色 / 深色外观，状态栏与导航条沉浸。
- 连接密码使用设备密钥 AES-GCM 加密保存。

English Changelog
- Connect to Oracle 19c (service name and SID fall back to each other, optional SYSDBA), MySQL / MariaDB and SQL Server (Microsoft JDBC, falling back to jTDS). The Oracle driver is rewritten at build time so it loads on Android.
- Database traffic is bound to the system VPN, so Tailscale subnet routes work. Unreachable hosts get one retry after re-binding the network, then a hint to check the VPN.
- Background keepalive: idle sessions send a heartbeat every 90 seconds and reconnect before the next run if the link dropped. On Android a foreground service with an ongoing notification keeps them alive, with a Disconnect all action.
- SQL editor: Alt+X runs the whole script and Ctrl+Enter the current statement, with `;`, a lone `/`, `GO` and `DELIMITER` as separators. Open and save `.sql` files (UTF-8 / GBK, up to 20 MB). Select all works in the editor.
- Writes and DDL ask for confirmation. History keeps the last 100 runs, with long-press multi-select delete and clear all.
- Result grid: measured, aligned column widths, lazy rows and optional column comments. Copy as TSV or export CSV (UTF-8 BOM). Optional unlimited rows.
- Object tree: one tree per session, with table, view and column comments. Long-press a table for its DDL, or a schema / database to export all DDL (Oracle `DBMS_METADATA`, MySQL `SHOW CREATE`, SQL Server built from the system catalog).
- Edit table data: change cells, set NULL, add and delete rows. Changes are previewed as SQL and committed in one transaction, rolled back if any row count is off. Rows are located by Oracle `ROWID` or the primary key.
- Edit table structure: add, change and drop columns and edit the table comment, with each engine's DDL shown before it runs.
- Miuix 0.9.4 UI: collapsible side navigation and a three-pane workbench on tablets, bottom navigation with Objects / SQL / Results tabs on phones. Progressive blur on top bars, Gaussian blur on the bottom bar, and an optional liquid-glass floating bar.
- About page with a flowing background, blended title and translucent cards that fade to a solid background on scroll, and clears the camera cutout and navigation bar in landscape.
- Simplified Chinese, Traditional Chinese and English, including Android 13 per-app language. System / light / dark appearance with edge-to-edge system bars.
- Connection passwords are stored encrypted with a device key (AES-GCM).

Version
- Version name: `1.0.0`
- Version code: `1`
