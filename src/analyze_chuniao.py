"""
分析春杪的聊天记录
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from importer_weflow import import_weflow
from pipeline import ProfilePipeline, save_profile

def main():
    print("=" * 60)
    print("分析春杪的聊天记录")
    print("=" * 60)

    # 导入聊天记录
    data_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'chuniao.json')
    print(f"\n导入聊天记录: {data_path}")

    messages = import_weflow(data_path, target_name='春杪', self_name='未然')
    print(f"导入完成: {len(messages)} 条消息")

    # 统计
    from collections import Counter
    type_counts = Counter(m.content_type.value for m in messages)
    print(f"消息类型: {dict(type_counts)}")

    # 只分析文本消息
    text_messages = [m for m in messages if m.content_type.value == 'text']
    print(f"文本消息: {len(text_messages)} 条")

    # 运行管道（不使用 LLM，先测试规则引擎）
    print("\n正在分析...")
    pipeline = ProfilePipeline(
        target_name='春杪',
        self_name='未然',
        use_llm=False,  # 先用规则引擎测试
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

    save_profile(profile, os.path.join(output_path, 'chuniao_profile.json'))
    with open(os.path.join(output_path, 'chuniao_report.md'), 'w', encoding='utf-8') as f:
        f.write(report)

    print(f"\n报告已保存到:")
    print(f"  - {os.path.join(output_path, 'chuniao_profile.json')}")
    print(f"  - {os.path.join(output_path, 'chuniao_report.md')}")


if __name__ == '__main__':
    main()
