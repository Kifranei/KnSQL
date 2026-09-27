package dev.knsql.i18n.texts

import dev.knsql.i18n.Text

internal val coreTexts = listOf(
    Text("语言", "語言", "Language"),
    Text("也可以在系统设置的应用语言里修改", "也可以在系統設定的應用程式語言中修改", "Can also be changed in system settings under app languages"),
    Text("项目主页", "專案首頁", "Project homepage"),
    Text("液态玻璃底栏", "液態玻璃底欄", "Liquid glass bottom bar"),
    Text(
        "竖屏时使用悬浮的液态玻璃导航栏；关闭时为高斯模糊底栏",
        "直向時使用懸浮的液態玻璃導覽列；關閉時為高斯模糊底欄",
        "Use the floating liquid glass navigation bar in portrait; when off, the bar uses a Gaussian blur",
    ),
    Text("不限制行数", "不限制列數", "No row limit"),
    Text(
        "取回查询的全部结果行。结果很大时会占用大量内存，平板上可能变慢。",
        "取回查詢的全部結果列。結果很大時會佔用大量記憶體，平板上可能變慢。",
        "Fetch every row a query returns. Large results use a lot of memory and can slow the tablet down.",
    ),
    Text("列注释", "欄註解", "Column comments"),
    Text(
        "这个结果来自多张表、视图联接或子查询，读不到列注释。",
        "這個結果來自多張資料表、檢視聯結或子查詢，讀不到欄註解。",
        "This result joins several tables or uses a subquery, so column comments are not available.",
    ),
    Text("连接失败：{0}", "連線失敗：{0}", "Connection failed: {0}"),
    Text(
        "连不上 {0}:{1}。如果数据库在 Tailscale 或其他 VPN 后面，确认 VPN 已连接后重试。",
        "連不上 {0}:{1}。如果資料庫在 Tailscale 或其他 VPN 後面，請確認 VPN 已連線後再試。",
        "Can't reach {0}:{1}. If the database is behind Tailscale or another VPN, make sure the VPN is connected and try again.",
    ),
)
