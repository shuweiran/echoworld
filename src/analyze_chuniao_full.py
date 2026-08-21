"""
完整 LLM 分析春杪聊天记录
"""
import sys
import os
import json
from datetime import datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from importer_weflow import import_weflow
from extractor_llm import LLMExtractor, SessionSplitter
from extractor_rules import RuleExtractor
from pipeline import ProfilePipeline, save_profile

def main():
    print("=" * 60)
    print("完整 LLM 分析春杪聊天记录")
    print("=" * 60)

    # 导入
    data_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data', 'chuniao.json')
    print(f'\n导入聊天记录...')
    messages = import_weflow(data_path, target_name='春杪', self_name='未然')
    text_msgs = [m for m in messages if m.content_type.value == 'text']
    print(f'文本消息: {len(text_msgs)} 条')

    # 会话切分
    splitter = SessionSplitter(gap_hours=2.0)
    sessions = splitter.split(text_msgs)
    print(f'总会话数: {len(sessions)}')

    # 过滤有意义的会话
    meaningful = []
    for s in sessions:
        target_msgs = [m for m in s if m.speaker_role.value == 'target']
        if len(s) >= 5 and len(target_msgs) >= 2:
            meaningful.append(s)
    print(f'有意义会话: {len(meaningful)} 个')

    # 初始化
    llm = LLMExtractor(
        model_name='deepseek-v4-flash',
        api_base='https://api.deepseek.com',
        api_key='***'
    )
    rule_extractor = RuleExtractor(window_size=10)

    # 规则提取
    print('\n[1/3] 规则提取...')
    rule_signals = rule_extractor.extract(text_msgs)
    print(f'  正向信号: {len(rule_signals["positive_signals"])}')
    print(f'  负向信号: {len(rule_signals["negative_signals"])}')
    print(f'  价格信号: {len(rule_signals["price_signals"])}')
    print(f'  分享信号: {len(rule_signals["share_signals"])}')

    # LLM 提取
    print(f'\n[2/3] LLM 分析 ({len(meaningful)} 个会话)...')
    all_results = []
    for i, session in enumerate(meaningful):
        if (i + 1) % 20 == 0:
            print(f'  进度: {i+1}/{len(meaningful)}')
        result = llm.extract_from_session(session, '春杪')
        if result.get('preferences') or result.get('gift_signals') or result.get('behavior_patterns'):
            all_results.append(result)

    print(f'  LLM 分析完成，发现 {len(all_results)} 个有意义的会话')

    # 聚合结果
    print('\n[3/3] 聚合结果...')
    all_preferences = []
    all_gift_signals = []
    all_behavior_patterns = []
    all_unknowns = []

    for result in all_results:
        all_preferences.extend(result.get('preferences', []))
        all_gift_signals.extend(result.get('gift_signals', []))
        all_behavior_patterns.extend(result.get('behavior_patterns', []))
        all_unknowns.extend(result.get('unknowns', []))

    # 去重
    seen_prefs = set()
    unique_preferences = []
    for p in all_preferences:
        key = f"{p.get('category')}:{p.get('object')}:{p.get('polarity')}"
        if key not in seen_prefs:
            seen_prefs.add(key)
            unique_preferences.append(p)

    seen_patterns = set()
    unique_patterns = []
    for bp in all_behavior_patterns:
        key = f"{bp.get('trigger')}:{bp.get('behavior')}"
        if key not in seen_patterns:
            seen_patterns.add(key)
            unique_patterns.append(bp)

    # 生成报告
    report = []
    report.append("# 👤 春杪 完整偏好画像报告 (LLM 分析)")
    report.append(f"\n📅 数据范围：2024-04 至 2026-05")
    report.append(f"📊 总消息数：{len(text_msgs)} | 会话数：{len(sessions)} | 有意义会话：{len(meaningful)}")
    report.append(f"🔍 LLM 分析会话：{len(all_results)} | 提取偏好：{len(unique_preferences)} 条")

    # 喜好
    likes = [p for p in unique_preferences if p.get('polarity') == 'like']
    dislikes = [p for p in unique_preferences if p.get('polarity') == 'dislike']

    if likes:
        report.append("\n## 💚 喜好清单")
        for p in likes:
            conf = p.get('confidence', 0)
            level = p.get('inference_level', 'unknown')
            report.append(f"\n### {p.get('category', '未知')}")
            report.append(f"- **{p.get('object', '未知')}** | 置信度 {conf:.0%} | {level}")
            report.append(f"  理由：{p.get('reasoning', '无')}")
            if p.get('evidence_quotes'):
                for q in p['evidence_quotes'][:2]:
                    report.append(f"  > 「{q}」")

    if dislikes:
        report.append("\n## ❤️ 避雷清单")
        for p in dislikes:
            conf = p.get('confidence', 0)
            report.append(f"- **{p.get('object', '未知')}** ({conf:.0%})")
            report.append(f"  理由：{p.get('reasoning', '无')}")
            if p.get('evidence_quotes'):
                for q in p['evidence_quotes'][:1]:
                    report.append(f"  > 「{q}」")

    # 礼物信号
    if all_gift_signals:
        report.append("\n## 🎁 礼物信号")
        for sig in all_gift_signals:
            report.append(f"- **{sig.get('object', '未知')}** (想要程度: {sig.get('desire', '未知')})")
            if sig.get('price_barrier'):
                report.append(f"  ⚠️ 有价格犹豫")
            if sig.get('evidence_quotes'):
                for q in sig['evidence_quotes'][:1]:
                    report.append(f"  > 「{q}」")

    # 行为模式
    if unique_patterns:
        report.append("\n## 🧠 行为模式")
        for bp in unique_patterns:
            conf = bp.get('confidence', 0)
            report.append(f"- **当 {bp.get('trigger', '未知')}** → {bp.get('behavior', '未知')} ({conf:.0%})")
            if bp.get('evidence_quotes'):
                for q in bp['evidence_quotes'][:1]:
                    report.append(f"  > 「{q}」")

    # 未知
    if all_unknowns:
        report.append("\n## ❓ 需要更多证据")
        # 去重
        unique_unknowns = list(set(all_unknowns))[:10]
        for u in unique_unknowns:
            report.append(f"- {u}")

    report_text = "\n".join(report)

    # 输出
    print("\n" + "=" * 60)
    print(report_text)

    # 保存
    output_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'output')
    os.makedirs(output_path, exist_ok=True)

    with open(os.path.join(output_path, 'chuniao_full_report.md'), 'w', encoding='utf-8') as f:
        f.write(report_text)

    # 保存原始数据
    with open(os.path.join(output_path, 'chuniao_full_data.json'), 'w', encoding='utf-8') as f:
        json.dump({
            'preferences': unique_preferences,
            'gift_signals': all_gift_signals,
            'behavior_patterns': unique_patterns,
            'unknowns': list(set(all_unknowns)),
        }, f, ensure_ascii=False, indent=2)

    print(f"\n报告已保存到:")
    print(f"  - {os.path.join(output_path, 'chuniao_full_report.md')}")
    print(f"  - {os.path.join(output_path, 'chuniao_full_data.json')}")

if __name__ == '__main__':
    main()
