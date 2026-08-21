"""
Chat Profile Miner - 主流程
串联 importer → extractor → aggregator → ranker 的完整管道。
"""
import json
import re
from typing import List, Dict, Optional
from datetime import datetime
from pathlib import Path

from schemas import (
    Message, ProfileSnapshot, Preference, GiftSignal, BehaviorPattern,
    Evidence, Confidence, Polarity, InferenceLevel, PreferenceStatus,
    EvidenceType, DesireLevel, GiftRecommendation
)
from importer import WeChatImporter, DouyinImporter, PlainTextImporter, ClipboardImporter
from extractor_rules import RuleExtractor
from extractor_llm import LLMExtractor, SessionSplitter
from gift_ranker import GiftRanker


class ProfilePipeline:
    """偏好画像管道"""

    def __init__(self,
                 target_name: str,
                 self_name: str = "我",
                 use_llm: bool = True,
                 llm_api_base: str = "https://api.deepseek.com",
                 llm_api_key: str = "",
                 llm_model: str = "deepseek-v4-flash"):
        self.target_name = target_name
        self.self_name = self_name
        self.use_llm = use_llm

        # 初始化组件
        self.rule_extractor = RuleExtractor(window_size=10)
        self.session_splitter = SessionSplitter(gap_hours=2.0)
        self.gift_ranker = GiftRanker()

        if use_llm:
            self.llm_extractor = LLMExtractor(
                model_name=llm_model,
                api_base=llm_api_base,
                api_key=llm_api_key
            )
        else:
            self.llm_extractor = None

    def run_from_file(self, file_path: str, platform: str = "auto") -> ProfileSnapshot:
        """从文件运行完整管道"""
        # 1. 导入
        messages = self._import_file(file_path, platform)
        return self.run_from_messages(messages)

    def run_from_text(self, text: str) -> ProfileSnapshot:
        """从粘贴文本运行完整管道"""
        importer = ClipboardImporter(self.target_name, self.self_name)
        messages = importer.import_text(text)
        return self.run_from_messages(messages)

    def run_from_messages(self, messages: List[Message]) -> ProfileSnapshot:
        """从消息列表运行完整管道"""
        if not messages:
            return self._empty_profile()

        # 2. 规则提取
        rule_signals = self.rule_extractor.extract(messages)

        # 3. 会话切分
        sessions = self.session_splitter.split(messages)

        # 4. LLM 抽取（可选）
        llm_results = []
        if self.use_llm and self.llm_extractor:
            for session in sessions:
                result = self.llm_extractor.extract_from_session(
                    session, self.target_name
                )
                llm_results.append(result)

        # 5. 聚合
        preferences = self._aggregate_preferences(rule_signals, llm_results)
        gift_signals = self._aggregate_gift_signals(rule_signals, llm_results, messages)
        behavior_patterns = self._aggregate_behavior_patterns(llm_results)
        avoid = self._extract_avoid(rule_signals, llm_results)

        # 6. 冲突检测
        preferences = self._detect_conflicts(preferences)

        # 7. 置信度校准
        preferences = self._calibrate_confidence(preferences)

        # 8. 排序礼物
        gift_recommendations = self.gift_ranker.rank_gifts(
            preferences, gift_signals, avoid
        )

        # 9. 构建画像
        unknowns = []
        for r in llm_results:
            unknowns.extend(r.get("unknowns", []))

        profile = ProfileSnapshot(
            profile_version="0.1.0",
            subject_id=self.target_name,
            generated_at=datetime.now(),
            data_range=self._get_data_range(messages),
            total_messages=len(messages),
            total_sessions=len(sessions),
            preferences=preferences,
            gift_signals=gift_signals,
            avoid=avoid,
            behavior_patterns=behavior_patterns,
            unknowns=unknowns,
            facts_extracted=sum(len(p.evidence) for p in preferences),
            preference_categories=len(set(p.category for p in preferences)),
        )

        return profile

    def _import_file(self, file_path: str, platform: str) -> List[Message]:
        """根据平台选择导入器"""
        if platform == "auto":
            # 自动检测
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                sample = f.read(2000)

            if "douyin" in file_path.lower() or "[" in sample[:500]:
                platform = "douyin"
            elif re.search(r'\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}', sample):
                platform = "wechat"
            else:
                platform = "plain"

        importers = {
            "wechat": WeChatImporter,
            "douyin": DouyinImporter,
            "plain": PlainTextImporter,
        }

        import re  # for auto-detection regex

        importer_cls = importers.get(platform, PlainTextImporter)
        importer = importer_cls(self.target_name, self.self_name)
        return importer.import_file(file_path)

    def _aggregate_preferences(self, rule_signals: Dict,
                               llm_results: List[Dict]) -> List[Preference]:
        """聚合规则和 LLM 的偏好结果"""
        preferences = []
        pref_counter = 0

        # 从规则信号构建偏好
        # 按品类+具体对象分组正向信号
        # 先过滤掉包含明确否定词的信号
        positive_by_entity = {}
        for sig in rule_signals.get("positive_signals", []):
            # 跳过包含明确否定的信号
            text = sig["message"].text
            if re.search(r'不喜欢|不.*喜欢|不要|不要了|算了|太甜|太冲|太浓|显老|太土|太成熟', text):
                continue
            entities = self.rule_extractor.extract_entities(sig["message"].text)
            categories = entities.get("categories", [])
            items = entities.get("items", [])
            colors = entities.get("colors", [])
            styles = entities.get("styles", [])

            # 构建具体描述
            if categories:
                for i, cat in enumerate(categories):
                    item_desc = items[i] if i < len(items) else cat
                    # 加上颜色和风格
                    parts = [item_desc]
                    if colors:
                        parts = colors[:1] + parts
                    if styles:
                        parts = styles[:1] + parts
                    desc = ", ".join(parts)
                    key = f"{cat}:{desc}"
                    if key not in positive_by_entity:
                        positive_by_entity[key] = {"category": cat, "object": desc, "signals": []}
                    positive_by_entity[key]["signals"].append(sig)
            else:
                # 没有识别到品类，用通用描述
                desc = ", ".join(colors + styles) if (colors or styles) else "未分类"
                key = f"unknown:{desc}"
                if key not in positive_by_entity:
                    positive_by_entity[key] = {"category": "unknown", "object": desc, "signals": []}
                positive_by_entity[key]["signals"].append(sig)

        for key, data in positive_by_entity.items():
            signals = data["signals"]
            pref_counter += 1
            evidence = [self.rule_extractor.build_evidence(s, s["evidence_type"])
                       for s in signals]
            preferences.append(Preference(
                preference_id=f"pref_{pref_counter:04d}",
                category=data["category"],
                object=data["object"],
                polarity=Polarity.LIKE,
                strength=max(s["weight"] for s in signals),
                status=PreferenceStatus.ACTIVE,
                inference_level=InferenceLevel.BEHAVIORAL_INFERENCE,
                evidence=evidence,
                confidence=Confidence(
                    raw_score=0.7,
                    calibrated_probability=0.65,
                    evidence_count=len(evidence),
                    explicit_count=sum(1 for e in evidence
                                     if e.evidence_type == EvidenceType.EXPLICIT_STATEMENT),
                    contradiction_count=0,
                ),
                first_seen=min(e.timestamp for e in evidence),
                last_seen=max(e.timestamp for e in evidence),
            ))

        # 从 LLM 结果构建偏好
        for result in llm_results:
            for pref_data in result.get("preferences", []):
                pref_counter += 1
                polarity = Polarity.LIKE if pref_data.get("polarity") == "like" else Polarity.DISLIKE
                inference_map = {
                    "explicit": InferenceLevel.EXPLICIT_FACT,
                    "direct_behavior": InferenceLevel.DIRECT_BEHAVIOR,
                    "behavioral_inference": InferenceLevel.BEHAVIORAL_INFERENCE,
                    "weak_inference": InferenceLevel.WEAK_INFERENCE,
                }
                inference_level = inference_map.get(
                    pref_data.get("inference_level", "weak_inference"),
                    InferenceLevel.WEAK_INFERENCE
                )

                evidence = []
                for quote in pref_data.get("evidence_quotes", []):
                    evidence.append(Evidence(
                        message_id=f"llm_{pref_counter}",
                        timestamp=datetime.now(),
                        evidence_type=EvidenceType.EXPLICIT_STATEMENT,
                        quote=quote,
                        support=pref_data.get("confidence", 0.5),
                    ))

                preferences.append(Preference(
                    preference_id=f"pref_{pref_counter:04d}",
                    category=pref_data.get("category", "unknown"),
                    object=pref_data.get("object", ""),
                    polarity=polarity,
                    strength=pref_data.get("confidence", 0.5),
                    status=PreferenceStatus.ACTIVE,
                    inference_level=inference_level,
                    evidence=evidence,
                    confidence=Confidence(
                        raw_score=pref_data.get("confidence", 0.5),
                        calibrated_probability=pref_data.get("confidence", 0.5),
                        evidence_count=len(evidence),
                        explicit_count=0,
                        contradiction_count=0,
                    ),
                ))

        return preferences

    def _aggregate_gift_signals(self, rule_signals: Dict,
                                llm_results: List[Dict],
                                messages: List[Message]) -> List[GiftSignal]:
        """聚合礼物信号"""
        signals = []
        sig_counter = 0

        # 从规则的价格犹豫信号中提取
        for price_sig in rule_signals.get("price_signals", []):
            if price_sig["is_barrier"]:
                # 查找前后文中的商品信息（只看前后2条）
                ctx_before = price_sig.get("context_before", [])[-2:]
                ctx_after = price_sig.get("context_after", [])[:2]
                context = ctx_before + [price_sig["message"].text] + ctx_after
                context_text = " ".join(context)
                entities = self.rule_extractor.extract_entities(context_text)

                categories = entities.get("categories", [])
                items = entities.get("items", [])
                colors = entities.get("colors", [])

                # 构建具体描述
                if items:
                    item_desc = items[0]
                    if colors:
                        item_desc = f"{colors[0]}{item_desc}"
                    category = categories[0] if categories else "unknown"
                elif categories:
                    category = categories[0]
                    item_desc = category
                else:
                    category = "unknown"
                    item_desc = "未知商品"

                sig_counter += 1
                signals.append(GiftSignal(
                    object=item_desc,
                    category=category,
                    desire=DesireLevel.HIGH,
                    price_barrier=True,
                    already_owned=False,
                    gift_score=0.9,
                    evidence_ids=[price_sig["message"].message_id],
                    confidence=Confidence(
                        raw_score=0.85,
                        calibrated_probability=0.80,
                        evidence_count=1,
                        explicit_count=0,
                        contradiction_count=0,
                    ),
                ))

        # 从 LLM 结果提取
        for result in llm_results:
            for sig_data in result.get("gift_signals", []):
                sig_counter += 1
                desire_map = {
                    "high": DesireLevel.HIGH,
                    "moderate": DesireLevel.MODERATE,
                    "low": DesireLevel.LOW,
                }
                signals.append(GiftSignal(
                    object=sig_data.get("object", ""),
                    category="unknown",
                    desire=desire_map.get(sig_data.get("desire", "moderate"),
                                         DesireLevel.MODERATE),
                    price_barrier=sig_data.get("price_barrier", False),
                    already_owned=False,
                    gift_score=sig_data.get("confidence", 0.5),
                    evidence_ids=[],
                    confidence=Confidence(
                        raw_score=sig_data.get("confidence", 0.5),
                        calibrated_probability=sig_data.get("confidence", 0.5),
                        evidence_count=len(sig_data.get("evidence_quotes", [])),
                        explicit_count=0,
                        contradiction_count=0,
                    ),
                ))

        return signals

    def _aggregate_behavior_patterns(self, llm_results: List[Dict]) -> List[BehaviorPattern]:
        """聚合行为模式"""
        patterns = []
        for result in llm_results:
            for bp_data in result.get("behavior_patterns", []):
                evidence = []
                for quote in bp_data.get("evidence_quotes", []):
                    evidence.append(Evidence(
                        message_id="llm_bp",
                        timestamp=datetime.now(),
                        evidence_type=EvidenceType.EXPLICIT_STATEMENT,
                        quote=quote,
                        support=bp_data.get("confidence", 0.5),
                    ))
                patterns.append(BehaviorPattern(
                    trigger=bp_data.get("trigger", ""),
                    behavior=bp_data.get("behavior", ""),
                    confidence=bp_data.get("confidence", 0.5),
                    evidence=evidence,
                ))
        return patterns

    def _extract_avoid(self, rule_signals: Dict,
                       llm_results: List[Dict]) -> List[Preference]:
        """提取避雷清单"""
        avoid = []
        counter = 0

        # 从规则负向信号提取
        # 先过滤掉价格犹豫信号（价格犹豫是礼物信号，不是避雷）
        price_msg_ids = {s["message"].message_id for s in rule_signals.get("price_signals", []) if s["is_barrier"]}
        negative_by_entity = {}
        for sig in rule_signals.get("negative_signals", []):
            # 跳过价格犹豫相关的消息
            if sig["message"].message_id in price_msg_ids:
                continue
            entities = self.rule_extractor.extract_entities(sig["message"].text)
            categories = entities.get("categories", [])
            items = entities.get("items", [])
            colors = entities.get("colors", [])
            styles = entities.get("styles", [])

            # 从上下文中提取被否定的对象（只看前后1条）
            ctx_before = sig.get("context_before", [])[-1:]
            ctx_after = sig.get("context_after", [])[:1]
            context = " ".join(ctx_before + [sig["message"].text] + ctx_after)
            ctx_entities = self.rule_extractor.extract_entities(context)
            ctx_categories = ctx_entities.get("categories", [])
            ctx_items = ctx_entities.get("items", [])
            ctx_colors = ctx_entities.get("colors", [])
            ctx_styles = ctx_entities.get("styles", [])

            # 使用当前消息的实体，如果当前消息没有则用上下文的
            all_categories = categories if categories else ctx_categories
            all_items = items if items else ctx_items
            all_colors = colors if colors else ctx_colors
            all_styles = styles if styles else ctx_styles

            if all_categories:
                for i, cat in enumerate(all_categories):
                    item_desc = all_items[i] if i < len(all_items) else cat
                    parts = [item_desc]
                    if all_colors:
                        parts = all_colors[:1] + parts
                    if all_styles:
                        parts = all_styles[:1] + parts
                    desc = ", ".join(parts)
                    key = f"{cat}:{desc}"
                    if key not in negative_by_entity:
                        negative_by_entity[key] = {"category": cat, "object": desc, "signals": []}
                    negative_by_entity[key]["signals"].append(sig)
            else:
                # 用上下文的颜色/风格作为描述
                desc = ", ".join(all_colors + all_styles) if (all_colors or all_styles) else "未分类"
                key = f"unknown:{desc}"
                if key not in negative_by_entity:
                    negative_by_entity[key] = {"category": "unknown", "object": desc, "signals": []}
                negative_by_entity[key]["signals"].append(sig)

        for key, data in negative_by_entity.items():
            signals = data["signals"]
            counter += 1
            evidence = [self.rule_extractor.build_evidence(s, s["evidence_type"])
                       for s in signals]
            avoid.append(Preference(
                preference_id=f"avoid_{counter:04d}",
                category=data["category"],
                object=data["object"],
                polarity=Polarity.DISLIKE,
                strength=max(s["weight"] for s in signals),
                status=PreferenceStatus.ACTIVE,
                inference_level=InferenceLevel.BEHAVIORAL_INFERENCE,
                evidence=evidence,
                confidence=Confidence(
                    raw_score=0.7,
                    calibrated_probability=0.65,
                    evidence_count=len(evidence),
                    explicit_count=0,
                    contradiction_count=0,
                ),
            ))

        # 从 LLM 结果提取
        for result in llm_results:
            for pref_data in result.get("preferences", []):
                if pref_data.get("polarity") == "dislike":
                    counter += 1
                    evidence = []
                    for quote in pref_data.get("evidence_quotes", []):
                        evidence.append(Evidence(
                            message_id=f"llm_avoid_{counter}",
                            timestamp=datetime.now(),
                            evidence_type=EvidenceType.NEGATIVE_REACTION,
                            quote=quote,
                            support=pref_data.get("confidence", 0.5),
                        ))
                    avoid.append(Preference(
                        preference_id=f"avoid_{counter:04d}",
                        category=pref_data.get("category", "unknown"),
                        object=pref_data.get("object", ""),
                        polarity=Polarity.DISLIKE,
                        strength=pref_data.get("confidence", 0.5),
                        status=PreferenceStatus.ACTIVE,
                        inference_level=InferenceLevel.BEHAVIORAL_INFERENCE,
                        evidence=evidence,
                        confidence=Confidence(
                            raw_score=pref_data.get("confidence", 0.5),
                            calibrated_probability=pref_data.get("confidence", 0.5),
                            evidence_count=len(evidence),
                            explicit_count=0,
                            contradiction_count=0,
                        ),
                    ))

        return avoid

    def _detect_conflicts(self, preferences: List[Preference]) -> List[Preference]:
        """检测偏好冲突"""
        # 简单实现：同一 category + object 的 like 和 dislike 标记为冲突
        by_key = {}
        for pref in preferences:
            key = f"{pref.category}:{pref.object}"
            if key not in by_key:
                by_key[key] = []
            by_key[key].append(pref)

        for key, prefs in by_key.items():
            likes = [p for p in prefs if p.polarity == Polarity.LIKE]
            dislikes = [p for p in prefs if p.polarity == Polarity.DISLIKE]

            if likes and dislikes:
                # 存在冲突
                for p in likes:
                    p.counter_evidence.extend(
                        dislikes[0].evidence[:2]  # 取最近的反证
                    )
                    p.confidence.contradiction_count += 1
                for p in dislikes:
                    p.counter_evidence.extend(
                        likes[0].evidence[:2]
                    )
                    p.confidence.contradiction_count += 1

        return preferences

    def _calibrate_confidence(self, preferences: List[Preference]) -> List[Preference]:
        """校准置信度（简化版）"""
        for pref in preferences:
            raw = pref.confidence.raw_score
            evidence_count = pref.confidence.evidence_count
            explicit_count = pref.confidence.explicit_count
            contradiction = pref.confidence.contradiction_count

            # 校准公式：
            # 基础分 = raw_score
            # 证据加分：每条证据 +5%，最多 +30%
            # 明确表达加分：每条 +10%，最多 +20%
            # 反证惩罚：每条 -15%
            evidence_bonus = min(evidence_count * 0.05, 0.3)
            explicit_bonus = min(explicit_count * 0.10, 0.2)
            contradiction_penalty = contradiction * 0.15

            adjusted = raw + evidence_bonus + explicit_bonus - contradiction_penalty
            pref.confidence.calibrated_probability = max(0.0, min(1.0, adjusted))

        return preferences

    def _get_data_range(self, messages: List[Message]) -> str:
        """获取数据时间范围"""
        if not messages:
            return "无数据"
        timestamps = [m.timestamp for m in messages if m.timestamp]
        if timestamps:
            start = min(timestamps).strftime("%Y-%m")
            end = max(timestamps).strftime("%Y-%m")
            return f"{start} 至 {end}"
        return "未知"

    def _empty_profile(self) -> ProfileSnapshot:
        """空画像"""
        return ProfileSnapshot(
            profile_version="0.1.0",
            subject_id=self.target_name,
            generated_at=datetime.now(),
            data_range="无数据",
            total_messages=0,
            total_sessions=0,
        )

    def generate_report(self, profile: ProfileSnapshot) -> str:
        """生成人类可读的画像报告"""
        lines = []
        lines.append(f"# 👤 {profile.subject_id} 的偏好画像报告")
        lines.append(f"\n📅 数据范围：{profile.data_range}")
        lines.append(f"📊 总消息数：{profile.total_messages} | 会话数：{profile.total_sessions}")
        lines.append(f"🔍 提取事实：{profile.facts_extracted} 条 | 偏好类别：{profile.preference_categories} 类")

        # 喜好清单
        likes = [p for p in profile.preferences if p.polarity == Polarity.LIKE]
        if likes:
            lines.append("\n## 💚 喜好清单")
            by_cat = {}
            for p in likes:
                if p.category not in by_cat:
                    by_cat[p.category] = []
                by_cat[p.category].append(p)

            for cat, prefs in by_cat.items():
                lines.append(f"\n### {cat}")
                for p in sorted(prefs, key=lambda x: x.confidence.calibrated_probability, reverse=True):
                    conf = p.confidence.calibrated_probability
                    ev_count = len(p.evidence)
                    last = p.last_seen.strftime("%m-%d") if p.last_seen else "未知"
                    lines.append(f"- {p.object} | 置信度 {conf:.0%} | {ev_count} 条证据 | 最近: {last}")
                    for ev in p.evidence[:2]:
                        lines.append(f"  > 「{ev.quote}」")

        # 避雷清单
        if profile.avoid:
            lines.append("\n## ❤️ 避雷清单")
            for a in profile.avoid:
                conf = a.confidence.calibrated_probability
                lines.append(f"- {a.object} ({conf:.0%})")
                for ev in a.evidence[:1]:
                    lines.append(f"  > 「{ev.quote}」")

        # 礼物推荐
        ranker = GiftRanker()
        gifts = ranker.rank_gifts(profile.preferences, profile.gift_signals, profile.avoid)
        if gifts:
            lines.append("\n## 🎁 礼物推荐 Top 5")
            for g in gifts[:5]:
                stars = "★" * int(g.confidence * 5) + "☆" * (5 - int(g.confidence * 5))
                lines.append(f"\n{g.rank}. **{g.item}** ({g.price_range})")
                lines.append(f"   匹配度：{g.confidence:.0%} {stars}")
                lines.append(f"   理由：{g.reasoning}")
                if g.evidence_summary:
                    lines.append(f"   证据：{' | '.join(g.evidence_summary[:3])}")
                if g.risks:
                    lines.append(f"   ⚠️ 风险：{', '.join(g.risks)}")

        # 行为模式
        if profile.behavior_patterns:
            lines.append("\n## 🧠 行为模式")
            for bp in profile.behavior_patterns:
                lines.append(f"- 当 {bp.trigger} → {bp.behavior} (置信度 {bp.confidence:.0%})")

        # 未知
        if profile.unknowns:
            lines.append("\n## ❓ 证据不足")
            for u in profile.unknowns[:5]:
                lines.append(f"- {u}")

        return "\n".join(lines)


def save_profile(profile: ProfileSnapshot, output_path: str):
    """保存画像为 JSON"""
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(profile.model_dump_json(indent=2))


def load_profile(input_path: str) -> ProfileSnapshot:
    """加载画像"""
    with open(input_path, 'r', encoding='utf-8') as f:
        return ProfileSnapshot.model_validate_json(f.read())
