"""
分析春杪的聊天记录 - 使用 LLM 模式
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from importer_weflow import import_weflow
from pipeline import ProfilePipeline, save_profile

def main():
    print("=" * 60)
    print("分析春杪的聊天记录 (LLM 模式)")
    print("=" * 60)

    # API Key
    api_key = "sk-3c333b574cbc4504bd44820c9a37d027"

    # 导入聊天记录
    data_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'chuniao.json')
    print(f"\n导入聊天记录: {data_path}")

    messages = import_weflow(data_path, target_name='春杪', self_name='未然')
    print(f"导入完成: {len(messages)} 条消息")

    # 只分析文本消息
    text_messages = [m for m in messages if m.content_type.value == 'text']
    print(f"文本消息: {len(text_messages)} 条")

    # 运行管道（使用 LLM）
    print("\n正在分析 (LLM 模式)...")
    pipeline = ProfilePipeline(
        target_name='春杪',
        self_name='未然',
        use_llm=True,
        llm_api_base="https://api.deepseek.com",
        llm_api_key=api_key,
        llm_model="deepseek-v4-flash",
    )

    profile = pipeline.run_from_messages(text_messages)

    # 生成报告
    report = pipeline.generate_report(profile)

    # 输出报告
    print("\n" + "=" * 60)
    print("分析报告")
    print("=" * 60)
    print(report)

    # 保存
    output_path = os.path.join(os.path.dirname(__file__), '..', 'output')
    os.makedirs(output_path, exist_ok=True)

    save_profile(profile, os.path.join(output_path, 'chuniao_profile_llm.json'))
    with open(os.path.join(output_path, 'chuniao_report_llm.md'), 'w', encoding='utf-8') as f:
        f.write(report)

    print(f"\n报告已保存到:")
    print(f"  - {os.path.join(output_path, 'chuniao_profile_llm.json')}")
    print(f"  - {os.path.join(output_path, 'chuniao_report_llm.md')}")


if __name__ == '__main__':
    main()
