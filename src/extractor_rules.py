"""
Chat Profile Miner - 显式规则提取器
用正则/关键词快速提取高置信度偏好信号。
"""
import re
from typing import List, Dict, Tuple, Optional
from datetime import datetime
from schemas import (
    Message, Evidence, EvidenceType, Preference, GiftSignal,
    Polarity, InferenceLevel, PreferenceStatus, Confidence,
    DesireLevel
)


# ============================================================
# 关键词规则库
# ============================================================

# 正向偏好关键词 + 权重
POSITIVE_PATTERNS = [
    (r'喜欢|好喜欢|超喜欢|超级喜欢|特别喜欢', 1.0, EvidenceType.EXPLICIT_STATEMENT),
    (r'想要|好想要|一直想|想买|想入手', 0.95, EvidenceType.EXPLICIT_STATEMENT),
    (r'种草|被种草|心动|心水', 0.9, EvidenceType.EXPLICIT_STATEMENT),
    (r'好看|好好看|好可爱|好漂亮|好美', 0.8, EvidenceType.POSITIVE_REACTION),
    (r'好香|好闻|味道好', 0.75, EvidenceType.POSITIVE_REACTION),
    (r'不错|挺好|可以|蛮好的', 0.6, EvidenceType.POSITIVE_REACTION),
    (r'适合|搭|配', 0.5, EvidenceType.POSITIVE_REACTION),
]

# 负向偏好关键词 + 权重
NEGATIVE_PATTERNS = [
    (r'不喜欢|不.*喜欢|讨厌|烦|恶心', 1.0, EvidenceType.NEGATIVE_REACTION),
    (r'不要买|别买|别给我买|不需要', 0.95, EvidenceType.EXPLICIT_STATEMENT),
    (r'不要|不要了|算了|不想要', 0.9, EvidenceType.EXPLICIT_STATEMENT),
    (r'不好看|好丑|丑|难看|土|太土|显老', 0.85, EvidenceType.NEGATIVE_REACTION),
    (r'太甜|太冲|太浓|太成熟|太夸张', 0.8, EvidenceType.NEGATIVE_REACTION),
    (r'不适合|不搭|跟我.*不搭', 0.75, EvidenceType.NEGATIVE_REACTION),
    (r'一般|还好|就那样', 0.4, EvidenceType.NEGATIVE_REACTION),
]

# 价格犹豫信号
PRICE_PATTERNS = [
    (r'舍不得|有点贵|太贵|好贵|价格.*劝退', True),
    (r'算了.*贵|贵.*算了', True),
    (r'八百多|一千多|两千多|好几百', True),
    (r'便宜|划算|性价比|白菜', False),
]

# 分享商品信号
SHARE_PATTERNS = [
    (r'https?://\S+', EvidenceType.SHARED_PRODUCT),
    (r'\[链接\]|\[商品\]|\[小程序\]', EvidenceType.SHARED_PRODUCT),
    (r'给你看|你看这个|快看|你看看', EvidenceType.SOCIAL_FORWARD),
]

# 拥有/购买信号
OWNERSHIP_PATTERNS = [
    (r'已经有了|买过了|入手了|到手了|收到了', True),
    (r'正在用|一直在用|每天都用|常用', True),
    (r'闲置了|吃灰|没用过|放着没动', False),
]

# 品类词典
CATEGORY_MAP = [
    (r'项链|吊坠|锁骨链|套链', 'jewelry', '项链'),
    (r'戒指|指环', 'jewelry', '戒指'),
    (r'耳环|耳钉|耳坠', 'jewelry', '耳环'),
    (r'手链|手镯|镯子', 'jewelry', '手链'),
    (r'香水|香氛|香薰|古龙', 'fragrance', '香水'),
    (r'口红|唇膏|唇釉|唇彩', 'makeup', '口红'),
    (r'眼影|腮红|粉底|遮瑕', 'makeup', '彩妆'),
    (r'包|包包|手提包|斜挎包|托特包', 'bag', '包包'),
    (r'毛绒|玩偶|公仔|抱枕|娃娃', 'plush', '毛绒玩偶'),
    (r'护肤|面霜|精华|面膜|水乳', 'skincare', '护肤品'),
    (r'耳机|音箱|音响', 'electronics', '电子产品'),
    (r'拍立得|相机|镜头', 'photography', '拍照设备'),
    (r'花|花束|永生花|干花', 'flowers', '花束'),
    (r'巧克力|甜品|蛋糕', 'food', '甜品'),
]

# 颜色词典
COLOR_MAP = [
    (r'银色|银的|银质', '银色'),
    (r'金色|黄金|金的', '金色'),
    (r'玫瑰金', '玫瑰金'),
    (r'黑色|黑的', '黑色'),
    (r'白色|白的', '白色'),
    (r'粉色|粉的', '粉色'),
    (r'蓝色|蓝的', '蓝色'),
    (r'红色|红的', '红色'),
]

# 风格词典
STYLE_MAP = [
    (r'简约|简单|朴素|低调', '简约'),
    (r'精致|小巧|细|纤细', '精致小巧'),
    (r'夸张|大|粗|显眼', '夸张'),
    (r'可爱|萌|少女|甜美', '可爱'),
    (r'成熟|优雅|高级|轻奢', '优雅'),
    (r'小众|独特|特别', '小众'),
    (r'清新|淡|清雅', '清新'),
    (r'浓|甜|馥郁', '浓郁'),
]


class RuleExtractor:
    """基于规则的偏好提取器"""

    def __init__(self, window_size: int = 10):
        self.window_size = window_size

    def extract(self, messages: List[Message]) -> Dict[str, List]:
        """从消息列表中提取所有规则匹配的信号"""
        results = {
            "positive_signals": [],
            "negative_signals": [],
            "price_signals": [],
            "share_signals": [],
            "ownership_signals": [],
        }

        target_msgs = [m for m in messages if m.speaker_role.value == "target"]

        for i, msg in enumerate(target_msgs):
            text = msg.text
            ctx_start = max(0, i - self.window_size)
            ctx_end = min(len(target_msgs), i + self.window_size + 1)
            context_before = [m.text for m in target_msgs[ctx_start:i]]
            context_after = [m.text for m in target_msgs[i+1:ctx_end]]

            for pattern, weight, evidence_type in POSITIVE_PATTERNS:
                if re.search(pattern, text):
                    results["positive_signals"].append({
                        "message": msg, "pattern": pattern,
                        "weight": weight, "evidence_type": evidence_type,
                        "context_before": context_before,
                        "context_after": context_after,
                    })

            for pattern, weight, evidence_type in NEGATIVE_PATTERNS:
                if re.search(pattern, text):
                    results["negative_signals"].append({
                        "message": msg, "pattern": pattern,
                        "weight": weight, "evidence_type": evidence_type,
                        "context_before": context_before,
                        "context_after": context_after,
                    })

            for pattern, is_barrier in PRICE_PATTERNS:
                if re.search(pattern, text):
                    results["price_signals"].append({
                        "message": msg, "pattern": pattern,
                        "is_barrier": is_barrier,
                        "context_before": context_before,
                        "context_after": context_after,
                    })

            for pattern, evidence_type in SHARE_PATTERNS:
                if re.search(pattern, text):
                    results["share_signals"].append({
                        "message": msg, "pattern": pattern,
                        "evidence_type": evidence_type,
                        "context_before": context_before,
                        "context_after": context_after,
                    })

            for pattern, is_owned in OWNERSHIP_PATTERNS:
                if re.search(pattern, text):
                    results["ownership_signals"].append({
                        "message": msg, "pattern": pattern,
                        "is_owned": is_owned,
                        "context_before": context_before,
                        "context_after": context_after,
                    })

        return results

    def extract_entities(self, text: str) -> Dict[str, List[str]]:
        """从文本中提取实体（品牌、品类、颜色、风格）"""
        entities = {
            "brands": [],
            "categories": [],
            "colors": [],
            "styles": [],
            "items": [],
        }

        # 品牌
        brand_patterns = [
            r'APM|APM\s*Monaco', r'施华洛世奇|Swarovski', r'潘多拉|Pandora',
            r'周大福|周大生|周生生|老凤祥|老庙', r'Coach|蔻驰',
            r'MK|Michael\s*Kors', r'LV|Louis\s*Vuitton|路易威登',
            r'香奈儿|Chanel', r'迪奥|Dior', r'爱马仕|Hermes', r'古驰|Gucci',
            r'雅诗兰黛|Estee\s*Lauder', r'兰蔻|Lancome',
            r'祖玛珑|Jo\s*Malone', r'蕉下|Beneunder', r'徕芬|Laifen',
            r'Jellycat|邦尼兔', r'乐高|LEGO', r'名创优品|MINISO',
        ]
        for bp in brand_patterns:
            if re.search(bp, text, re.IGNORECASE):
                entities["brands"].append(bp.split('|')[0])

        # 品类
        for cp, cat, item_name in CATEGORY_MAP:
            if re.search(cp, text):
                entities["categories"].append(cat)
                entities["items"].append(item_name)

        # 颜色
        for cp, color_name in COLOR_MAP:
            if re.search(cp, text):
                entities["colors"].append(color_name)

        # 风格
        for sp, style_name in STYLE_MAP:
            if re.search(sp, text):
                entities["styles"].append(style_name)

        return entities

    def build_evidence(self, signal: dict, evidence_type: EvidenceType) -> Evidence:
        """从信号构建证据对象"""
        msg = signal["message"]
        return Evidence(
            message_id=msg.message_id,
            timestamp=msg.timestamp,
            evidence_type=evidence_type,
            quote=msg.text[:200],
            support=signal.get("weight", 0.5),
            context_before=signal.get("context_before", [])[:3],
            context_after=signal.get("context_after", [])[:3],
        )

