package dev.knsql.i18n.texts

import dev.knsql.i18n.Text

internal val ddlTexts = listOf<Text>(
    // Missing names / wrong selection
    Text("缺少 Schema", "缺少 Schema", "Missing schema"),
    Text("缺少表名", "缺少資料表名稱", "Missing table name"),
    Text("缺少视图名", "缺少檢視名稱", "Missing view name"),
    Text("缺少数据库名", "缺少資料庫名稱", "Missing database name"),
    Text("选择一个表、视图或 Schema", "選擇一個資料表、檢視或 Schema", "Select a table, view or schema"),
    Text("选择一个表、视图或数据库", "選擇一個資料表、檢視或資料庫", "Select a table, view or database"),
    Text("选择一个表、视图、Schema 或数据库", "選擇一個資料表、檢視、Schema 或資料庫", "Select a table, view, schema or database"),

    // Progress
    Text("读取表 {0}.{1}", "讀取資料表 {0}.{1}", "Reading table {0}.{1}"),
    Text("读取视图 {0}.{1}", "讀取檢視 {0}.{1}", "Reading view {0}.{1}"),
    Text("读取 {0}.{1}", "讀取 {0}.{1}", "Reading {0}.{1}"),
    Text("读取索引", "讀取索引", "Reading indexes"),
    Text("读取触发器", "讀取觸發程序", "Reading triggers"),
    Text("读取触发器 {0}", "讀取觸發程序 {0}", "Reading trigger {0}"),
    Text("读取注释", "讀取註解", "Reading comments"),
    Text("读取数据库 {0}", "讀取資料庫 {0}", "Reading database {0}"),
    Text("读取视图、函数与存储过程", "讀取檢視、函式與預存程序", "Reading views, functions and stored procedures"),
    Text("列出 {0} 的对象", "列出 {0} 的物件", "Listing objects in {0}"),
    Text("列出索引", "列出索引", "Listing indexes"),
    Text("列出外键", "列出外鍵", "Listing foreign keys"),
    Text("列出表", "列出資料表", "Listing tables"),

    // Object kinds (section titles and progress labels)
    Text("{0}（{1}）", "{0}（{1}）", "{0} ({1})"),
    Text("表", "資料表", "Tables", context = "ddl"),
    Text("视图", "檢視", "Views", context = "ddl"),
    Text("物化视图", "具體化檢視", "Materialized views"),
    Text("序列", "序列", "Sequences"),
    Text("类型", "型別", "Types"),
    Text("索引", "索引", "Indexes"),
    Text("注释", "註解", "Comments"),
    Text("外键", "外鍵", "Foreign keys"),
    Text("函数", "函式", "Functions"),
    Text("存储过程", "預存程序", "Stored procedures"),
    Text("函数与存储过程", "函式與預存程序", "Functions and stored procedures"),
    Text("例程", "常式", "Routines"),
    Text("包", "套件", "Packages"),
    Text("触发器", "觸發程序", "Triggers"),
    Text("同义词", "同義詞", "Synonyms"),
    Text("其他", "其他", "Other"),
    Text("触发器 {0}", "觸發程序 {0}", "trigger {0}"),

    // Comments written into the generated script
    Text("无法导出 {0} {1}.{2}：{3}", "無法匯出 {0} {1}.{2}：{3}", "Could not export {0} {1}.{2}: {3}"),
    Text("无法导出 {0}：{1}", "無法匯出 {0}：{1}", "Could not export {0}: {1}"),
    Text("无法导出 {0}：当前账号没有查看定义的权限", "無法匯出 {0}：目前帳號沒有查看定義的權限", "Could not export {0}: the current account has no permission to view its definition"),
    Text("无法导出表 {0}：{1}", "無法匯出資料表 {0}：{1}", "Could not export table {0}: {1}"),
    Text("无法导出视图 {0}：{1}", "無法匯出檢視 {0}：{1}", "Could not export view {0}: {1}"),
    Text("无法导出触发器 {0}：{1}", "無法匯出觸發程序 {0}：{1}", "Could not export trigger {0}: {1}"),
    Text("无法读取 {0}.{1} 的定义", "無法讀取 {0}.{1} 的定義", "Could not read the definition of {0}.{1}"),
    Text(
        "无法读取 {0}.{1} 的定义，可能已加密或没有 VIEW DEFINITION 权限",
        "無法讀取 {0}.{1} 的定義，可能已加密或沒有 VIEW DEFINITION 權限",
        "Could not read the definition of {0}.{1}; it may be encrypted or VIEW DEFINITION permission is missing",
    ),
    Text("找不到表 {0}.{1}，或者没有查看权限", "找不到資料表 {0}.{1}，或者沒有查看權限", "Table {0}.{1} not found, or no permission to view it"),
    Text("跳过 {0} 索引 {1}", "略過 {0} 索引 {1}", "Skipped {0} index {1}"),
    Text(
        "{0} 下没有可导出的对象，或者当前账号没有查看权限。",
        "{0} 下沒有可匯出的物件，或者目前帳號沒有查看權限。",
        "Nothing to export in {0}, or the current account has no permission to view its objects.",
    ),
)
