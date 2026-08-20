/**
 * uiProtoV2.ts — 剧本杀 UI 重设计「ui-proto-v2」特性开关（决策记录 U11 / D2/D7 执行须知 #2）
 *
 * 项目无既有特性开关模式（文档检索无先例）——采用「全局常量 + 环境变量占位」：
 *   - 默认开启（渐进嵌入第一步即生效，可验证三栏布局）
 *   - 一键回退：构建时设环境变量 `VITE_UI_PROTO_V2=0`（vite 注入 import.meta.env），
 *     布局走既有旧代码路径（ChatPage 现有沉浸模式 / 一般模式 / 狼人杀现状全部不动）
 *
 * 用法：`import { UI_PROTO_V2_ENABLED } from '../uiProtoV2'`
 */
export const UI_PROTO_V2_ENABLED: boolean =
  (import.meta.env?.VITE_UI_PROTO_V2 ?? '1') !== '0';
