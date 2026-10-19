package com.cookiebox.app.mcp

/**
 * MCP 页面注入的统一判定入口（ADR-0004 §2：click / fill / eval_js 等新注入路径必须走统一判定）。
 *
 * T-016（可配置的敏感页规则 / ScriptPolicy）尚未实现。按主人 2026-10-20 的裁决，T-029 先行：
 * 这里默认放行，所有 MCP 页面工具注入脚本前都调用 [allows]；T-016 完成后只改这一处，
 * 改为询问统一的 ScriptPolicy（TASKS.md 对 T-029 的约定是「走统一判定入口但 MCP 默认放行」）。
 */
object McpInjectPolicy {

    /** tool 为工具名，url 为当前页面地址。返回 false 时工具拒绝注入 */
    @Suppress("UNUSED_PARAMETER")
    fun allows(tool: String, url: String): Boolean = true
}
