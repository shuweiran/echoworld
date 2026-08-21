"""
分析春杪的聊天记录 - 使用 LLM 模式（带进度显示）
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from importer_weflow import import_weflow
from extractor_llm import SessionSplitter, LLMExtractor
from extractor_rules import RuleExtractor
from pipeline import ProfilePipeline, save_profile

def main():
    print("=" * 60)
    print("分析春杪的聊天记录 (LLM 模式)")
    print("=" * 60)

    # API Key
    api_key = "***"

    # 导入聊天记录
    data_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'chuniao.json')
    print(f"\n导入聊天记录: {data_path}")

    messages = import_weflow(data_path, target_name='春杪', self_name='未然')
    print(f"导入完成: {len(messages)} 条消息")

    # 只分析文本消息
    text_messages = [m for m in messages if m.content_type.value == 'text']
    print(f"文本消息: {len(text_messages)} 条")

    # 会话切分
    splitter = SessionSplitter(gap_hours=2.0)
    sessions = splitter.split(text_messages)
    print(f"会话数: {len(sessions)}")

    # 过滤有意义的会话
    meaningful_sessions = []
    for session in sessions:
        target_msgs = [m for m in session if m.speaker_role.value == 'target']
        if len(session) >= 5 and len(target_msgs) >= 2:
            meaningful_sessions.append(session)
    print(f"有意义的会话数: {len(meaningful_sessions)}")

    # 初始化 LLM 提取器
    llm = LLMExtractor(
        model_name="deepseek-v4-flash",
        api_base="https://api.deepseek.com",
        api_key=api_key
    )

    # 分析前10个会话作为测试
    print("\n正在分析前10个会话...")
    for i, session in enumerate(meaningful_sessions[:10]):
        print(f"\n--- 会话 {i+1} ({len(session)} 条消息) ---")
        # 打印会话内容
        for msg in session[:5]:
            print(f"  [{msg.timestamp.strftime('%m-%d %H:%M')}] {msg.speaker}: {msg.text[:50]}")
        if len(session) > 5:
            print(f"  ... 还有 {len(session)-5} 条消息")

        # LLM 分析
        result = llm.extract_from_session(session, '春杪')
        if result.get('preferences') or result.get('gift_signals'):
            print(f"  发现偏好: {len(result.get('preferences', []))} 条")
            for pref in result.get('preferences', []):
                print(f"    - {pref.get('category')}: {pref.get('object')} ({pref.get('polarity')}) [{pref.get('inference_level')}]")
                print(f"      证据: {pref.get('evidence_quotes', [])[:2]}")
            if result.get('gift_signals'):
                print(f"  礼物信号: {len(result.get('gift_signals', []))} 条")
                for sig in result.get('gift_signals', []):
                    print(f"    - {sig.get('object')} (想要程度: {sig.get('desire')})")
        else:
            print(f"  未发现明显偏好")

    print("\n" + "=" * 60)
    print("测试完成！如果结果合理，可以运行完整分析。")
    print("=" * 60)


if __name__ == '__main__':
    main()

