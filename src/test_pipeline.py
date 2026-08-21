"""
Chat Profile Miner - 测试
用模拟数据验证管道流程。
"""
from datetime import datetime
from schemas import Message, Platform, SpeakerRole, ContentType
from pipeline import ProfilePipeline


def create_test_messages() -> list:
    """创建测试用的聊天记录"""
    messages = [
        # 银色首饰偏好
        Message(
            message_id="t001", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 5, 13, 22, 16),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="这个银色的项链好好看", source_file="test", source_offset=0
        ),
        Message(
            message_id="t002", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 5, 13, 22, 17),
            speaker="我", speaker_role=SpeakerRole.SELF,
            content_type=ContentType.TEXT,
            text="买呗", source_file="test", source_offset=100
        ),
        Message(
            message_id="t003", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 5, 13, 22, 18),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="算了八百多有点舍不得", source_file="test", source_offset=200
        ),
        # 金色不喜欢
        Message(
            message_id="t004", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 6, 7, 17, 52),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="金色感觉有点显老", source_file="test", source_offset=300
        ),
        # 多次分享银色
        Message(
            message_id="t005", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 6, 15, 10, 30),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="这个银色的好看诶", source_file="test", source_offset=400
        ),
        Message(
            message_id="t006", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 7, 1, 14, 20),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.LINK,
            text="给你看这个银色的锁骨链", source_file="test", source_offset=500
        ),
        # 香水
        Message(
            message_id="t007", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 7, 10, 20, 0),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="这个香水好香 清新的那种", source_file="test", source_offset=600
        ),
        Message(
            message_id="t008", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 7, 10, 20, 5),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="不喜欢太甜的 有点冲", source_file="test", source_offset=700
        ),
        # 毛绒玩具
        Message(
            message_id="t009", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 8, 1, 15, 0),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="这个小熊好可爱", source_file="test", source_offset=800
        ),
        Message(
            message_id="t010", platform=Platform.DOUYIN,
            conversation_id="test", timestamp=datetime(2026, 8, 1, 15, 5),
            speaker="春杪", speaker_role=SpeakerRole.TARGET,
            content_type=ContentType.TEXT,
            text="但是太大了占地方", source_file="test", source_offset=900
        ),
    ]
    return messages


def test_pipeline():
    """测试完整管道"""
    print("=" * 60)
    print("Chat Profile Miner - 测试")
    print("=" * 60)

    messages = create_test_messages()
    print(f"\n📥 测试消息数: {len(messages)}")

    # 不使用 LLM 测试（纯规则）
    pipeline = ProfilePipeline(
        target_name="春杪",
        self_name="我",
        use_llm=False,  # 先测试规则引擎
    )

    profile = pipeline.run_from_messages(messages)

    print(f"\n📊 分析结果:")
    print(f"  数据范围: {profile.data_range}")
    print(f"  总消息数: {profile.total_messages}")
    print(f"  会话数: {profile.total_sessions}")
    print(f"  提取事实: {profile.facts_extracted}")
    print(f"  偏好类别: {profile.preference_categories}")

    print(f"\n💚 喜好 ({len(profile.preferences)} 条):")
    for p in profile.preferences:
        if p.polarity.value == "like":
            print(f"  - {p.category}: {p.object} (置信度 {p.confidence.calibrated_probability:.0%})")
            for ev in p.evidence[:2]:
                print(f"    > 「{ev.quote}」")

    print(f"\n❤️ 避雷 ({len(profile.avoid)} 条):")
    for a in profile.avoid:
        print(f"  - {a.category}: {a.object} (置信度 {a.confidence.calibrated_probability:.0%})")
        for ev in a.evidence[:1]:
            print(f"    > 「{ev.quote}」")

    print(f"\n🎁 礼物信号 ({len(profile.gift_signals)} 条):")
    for sig in profile.gift_signals:
        print(f"  - {sig.object} (想要程度: {sig.desire.value}, 价格犹豫: {sig.price_barrier})")

    # 生成报告
    report = pipeline.generate_report(profile)
    print(f"\n{'=' * 60}")
    print("📝 完整报告:")
    print("=" * 60)
    print(report)

    # 保存
    from pipeline import save_profile
    save_profile(profile, "test_profile.json")
    print(f"\n✅ 画像已保存到 test_profile.json")


if __name__ == "__main__":
    test_pipeline()
