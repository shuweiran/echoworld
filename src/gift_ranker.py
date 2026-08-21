"""
Chat Profile Miner - 礼物排序器
基于偏好强度、证据数量、时效性等因子排序礼物候选。
"""
from typing import List, Dict, Tuple
from datetime import datetime, timedelta
from schemas import (
    Preference, GiftSignal, GiftRecommendation, Confidence,
    Polarity, InferenceLevel, EvidenceType
)


# ============================================================
# 礼物排序公式
# ============================================================
#
# score = W·explicit_want + R·repetition + T·recency
#       + B·behavior + F·style_fit + N·novelty - P·risk
#
# W: 明确表达想要/喜欢 (权重 3.0)
# R: 重复提及次数 (权重 2.0)
# T: 最近是否仍然喜欢 (权重 1.5)
# B: 实际行为：分享、试戴、逛店、收藏 (权重 2.5)
# F: 是否符合她稳定的风格偏好 (权重 1.0)
# N: 是否是她尚未拥有的新东西 (权重 1.5)
# P: 风险：已拥有、明确讨厌、尺寸不确定、偏好已改变 (减分)

WEIGHTS = {
    "explicit_want": 3.0,
    "repetition": 2.0,
    "recency": 1.5,
    "behavior": 2.5,
    "style_fit": 1.0,
    "novelty": 1.5,
    "risk_penalty": 2.0,
}


class GiftRanker:
    """礼物排序器"""

    def __init__(self, now: datetime = None):
        self.now = now or datetime.now()

    def rank_gifts(self, preferences: List[Preference],
                   gift_signals: List[GiftSignal],
                   avoid: List[Preference],
                   budget_min: float = 0,
                   budget_max: float = float('inf')
                   ) -> List[GiftRecommendation]:
        """
        综合偏好和礼物信号，输出排序后的礼物推荐。
        """
        candidates = []

        # 从正向偏好中提取礼物候选
        for pref in preferences:
            if pref.polarity == Polarity.LIKE and pref.confidence.calibrated_probability >= 0.4:
                score = self._score_preference(pref)
                candidates.append({
                    "category": pref.category,
                    "item": pref.object,
                    "score": score,
                    "preference": pref,
                    "gift_signal": None,
                })

        # 从礼物信号中提取候选
        for sig in gift_signals:
            if sig.desire.value in ("high", "moderate"):
                score = self._score_gift_signal(sig)
                candidates.append({
                    "category": sig.category,
                    "item": sig.object,
                    "score": score,
                    "preference": None,
                    "gift_signal": sig,
                })

        # 去重合并
        merged = self._merge_candidates(candidates)

        # 排除避雷项
        avoid_objects = {a.object.lower() for a in avoid}
        merged = [c for c in merged if c["item"].lower() not in avoid_objects]

        # 按分数排序
        merged.sort(key=lambda x: x["score"], reverse=True)

        # 生成推荐
        recommendations = []
        for i, cand in enumerate(merged[:10]):
            pref = cand.get("preference")
            sig = cand.get("gift_signal")

            # 收集证据
            evidence_summary = []
            if pref:
                for ev in pref.evidence[:3]:
                    evidence_summary.append(f"「{ev.quote}」({ev.timestamp.strftime('%m-%d')})")
            if sig:
                evidence_summary.extend([f"证据ID: {eid}" for eid in sig.evidence_ids[:3]])

            # 风险评估
            risks, risk_level = self._assess_risks(cand, avoid)

            # 置信度
            confidence = 0.0
            if pref:
                confidence = pref.confidence.calibrated_probability
            elif sig:
                confidence = sig.confidence.calibrated_probability

            recommendations.append(GiftRecommendation(
                rank=i + 1,
                category=cand["category"],
                item=cand["item"],
                price_range=self._estimate_price_range(cand["category"]),
                confidence=confidence,
                reasoning=self._build_reasoning(cand),
                evidence_summary=evidence_summary,
                risk_level=risk_level,
                risks=risks,
            ))

        return recommendations

    def _score_preference(self, pref: Preference) -> float:
        """对偏好计算礼物分数"""
        score = 0.0

        # W: 明确表达
        explicit_count = sum(1 for e in pref.evidence
                           if e.evidence_type in (EvidenceType.EXPLICIT_STATEMENT,))
        score += WEIGHTS["explicit_want"] * min(explicit_count / 3, 1.0)

        # R: 重复次数
        score += WEIGHTS["repetition"] * min(len(pref.evidence) / 5, 1.0)

        # T: 时效性
        if pref.last_seen:
            days_ago = (self.now - pref.last_seen).days
            recency = max(0, 1 - days_ago / 365)
            score += WEIGHTS["recency"] * recency

        # B: 行为证据
        behavior_count = sum(1 for e in pref.evidence
                           if e.evidence_type in (
                               EvidenceType.SHARED_PRODUCT,
                               EvidenceType.PURCHASE,
                               EvidenceType.USAGE,
                               EvidenceType.SOCIAL_FORWARD
                           ))
        score += WEIGHTS["behavior"] * min(behavior_count / 3, 1.0)

        # F: 风格一致性（如果有反证少说明一致性高）
        contradiction_ratio = len(pref.counter_evidence) / max(len(pref.evidence), 1)
        score += WEIGHTS["style_fit"] * (1 - contradiction_ratio)

        # N: 新鲜度（没有拥有记录加分）
        has_ownership = any(e.evidence_type == EvidenceType.PURCHASE for e in pref.evidence)
        if not has_ownership:
            score += WEIGHTS["novelty"] * 0.8

        # P: 风险惩罚
        if pref.counter_evidence:
            score -= WEIGHTS["risk_penalty"] * min(len(pref.counter_evidence) / 3, 0.5)

        return max(0, min(score, 10.0))

    def _score_gift_signal(self, sig: GiftSignal) -> float:
        """对礼物信号计算分数"""
        score = 0.0

        if sig.desire.value == "high":
            score += WEIGHTS["explicit_want"] * 1.0
        elif sig.desire.value == "moderate":
            score += WEIGHTS["explicit_want"] * 0.5

        if sig.price_barrier:
            # 价格犹豫 = 想要但舍不得，是极强礼物信号
            score += 2.0

        if not sig.already_owned:
            score += WEIGHTS["novelty"] * 1.0

        return max(0, min(score, 10.0))

    def _merge_candidates(self, candidates: List[Dict]) -> List[Dict]:
        """合并相同品类的候选"""
        merged = {}
        for cand in candidates:
            key = f"{cand['category']}:{cand['item']}"
            if key in merged:
                merged[key]["score"] = max(merged[key]["score"], cand["score"])
                if cand.get("preference"):
                    merged[key]["preference"] = cand["preference"]
                if cand.get("gift_signal"):
                    merged[key]["gift_signal"] = cand["gift_signal"]
            else:
                merged[key] = cand
        return list(merged.values())

    def _assess_risks(self, cand: Dict, avoid: List[Preference]) -> Tuple[List[str], str]:
        """评估礼物风险"""
        risks = []
        pref = cand.get("preference")

        if pref and pref.counter_evidence:
            risks.append(f"存在 {len(pref.counter_evidence)} 条反向证据")

        if pref and pref.confidence.contradiction_count > 0:
            risks.append("偏好可能存在变化")

        # 检查是否在避雷清单相关品类
        for a in avoid:
            if a.category == cand["category"]:
                risks.append(f"该品类存在避雷项：{a.object}")

        if not risks:
            return [], "low"
        elif len(risks) <= 2:
            return risks, "medium"
        else:
            return risks, "high"

    def _estimate_price_range(self, category: str) -> str:
        """估算品类价格范围"""
        ranges = {
            "jewelry": "300-1500元",
            "fragrance": "200-600元",
            "makeup": "100-500元",
            "bag": "300-2000元",
            "plush": "50-300元",
            "electronics": "200-1000元",
            "photography": "300-2000元",
            "flowers": "100-500元",
            "food": "100-300元",
        }
        return ranges.get(category, "100-500元")

    def _build_reasoning(self, cand: Dict) -> str:
        """构建推荐理由"""
        parts = []
        pref = cand.get("preference")
        sig = cand.get("gift_signal")

        if pref:
            explicit = sum(1 for e in pref.evidence
                         if e.evidence_type == EvidenceType.EXPLICIT_STATEMENT)
            shared = sum(1 for e in pref.evidence
                        if e.evidence_type == EvidenceType.SHARED_PRODUCT)
            if explicit:
                parts.append(f"明确表达 {explicit} 次")
            if shared:
                parts.append(f"主动分享 {shared} 次")
            if pref.confidence.calibrated_probability:
                parts.append(f"置信度 {pref.confidence.calibrated_probability:.0%}")

        if sig:
            if sig.price_barrier:
                parts.append("有价格犹豫（想要但舍不得）")
            if sig.desire.value == "high":
                parts.append("明确想要")

        return "，".join(parts) if parts else "综合偏好分析"
