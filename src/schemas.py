"""
Chat Profile Miner - 数据模型定义
基于 Pydantic 的结构化 schema，所有中间结果和最终输出都通过这些模型约束。
"""
from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from enum import Enum


# ============================================================
# 1. 统一消息格式
# ============================================================

class Platform(str, Enum):
    WECHAT = "wechat"
    QQ = "qq"
    DOUYIN = "douyin"
    TELEGRAM = "telegram"
    FEISHU = "feishu"
    UNKNOWN = "unknown"


class SpeakerRole(str, Enum):
    SELF = "self"
    TARGET = "target"  # 分析对象
    OTHER = "other"


class ContentType(str, Enum):
    TEXT = "text"
    IMAGE = "image"
    VIDEO = "video"
    LINK = "link"
    FILE = "file"
    STICKER = "sticker"
    VOICE = "voice"


class Message(BaseModel):
    """统一消息格式 - 所有平台的聊天记录都转成这个格式"""
    message_id: str
    platform: Platform
    conversation_id: str
    timestamp: datetime
    speaker: str  # 发送者标识
    speaker_role: SpeakerRole
    content_type: ContentType
    text: str
    reply_to: Optional[str] = None  # 回复的消息ID
    source_file: str
    source_offset: int  # 原文件中的位置，用于证据溯源
    attachments: List[str] = Field(default_factory=list)


# ============================================================
# 2. 证据系统
# ============================================================

class EvidenceType(str, Enum):
    EXPLICIT_STATEMENT = "explicit_statement"  # 本人明确说过
    SHARED_PRODUCT = "shared_product"          # 分享商品链接/截图
    PURCHASE = "purchase"                      # 实际购买
    USAGE = "usage"                            # 持续使用
    POSITIVE_REACTION = "positive_reaction"    # 正向反应
    NEGATIVE_REACTION = "negative_reaction"    # 负向反应
    PRICE_OBJECTION = "price_objection"        # 价格犹豫
    SOCIAL_FORWARD = "social_forward"          # 给你发过相关链接


class Evidence(BaseModel):
    """单条证据"""
    message_id: str
    timestamp: datetime
    evidence_type: EvidenceType
    quote: str          # 原话
    support: float      # 该证据对结论的支持度 0-1
    context_before: List[str] = Field(default_factory=list)  # 前文
    context_after: List[str] = Field(default_factory=list)   # 后文


class Confidence(BaseModel):
    """置信度"""
    raw_score: float
    calibrated_probability: float
    evidence_count: int
    explicit_count: int
    contradiction_count: int


# ============================================================
# 3. 偏好数据
# ============================================================

class InferenceLevel(str, Enum):
    EXPLICIT_FACT = "explicit_fact"          # 本人明确说过
    DIRECT_BEHAVIOR = "direct_behavior"      # 本人实际做过
    BEHAVIORAL_INFERENCE = "behavioral_inference"  # 多条行为归纳
    WEAK_INFERENCE = "weak_inference"        # 证据有限
    UNKNOWN = "unknown"                      # 没有足够信息


class Polarity(str, Enum):
    LIKE = "like"
    DISLIKE = "dislike"
    NEUTRAL = "neutral"
    MIXED = "mixed"  # 有正有负


class PreferenceStatus(str, Enum):
    ACTIVE = "active"      # 当前仍有效
    CHANGED = "changed"    # 已改变
    UNCERTAIN = "uncertain"  # 不确定


class Preference(BaseModel):
    """单条偏好"""
    preference_id: str
    category: str           # jewelry / fragrance / fashion / food / ...
    object: str             # 银色细项链 / 浓甜香 / ...
    polarity: Polarity
    strength: float         # 偏好强度 0-1
    status: PreferenceStatus
    inference_level: InferenceLevel
    evidence: List[Evidence]
    counter_evidence: List[Evidence] = Field(default_factory=list)
    confidence: Confidence
    first_seen: Optional[datetime] = None
    last_seen: Optional[datetime] = None
    change_history: List[dict] = Field(default_factory=list)  # 偏好变化记录


# ============================================================
# 4. 礼物信号
# ============================================================

class DesireLevel(str, Enum):
    HIGH = "high"           # 明确想要
    MODERATE = "moderate"   # 有兴趣
    LOW = "low"             # 只是觉得好看


class GiftSignal(BaseModel):
    """礼物信号"""
    object: str
    category: str
    brand: Optional[str] = None
    desire: DesireLevel
    price_barrier: bool = False  # 是否有价格犹豫
    already_owned: bool = False
    gift_score: float  # 礼物推荐分 0-1
    evidence_ids: List[str]
    confidence: Confidence


# ============================================================
# 5. 行为模式
# ============================================================

class BehaviorPattern(BaseModel):
    """if-then 行为模式"""
    trigger: str        # 触发条件
    behavior: str       # 行为表现
    confidence: float
    evidence: List[Evidence]
    counter_evidence: List[Evidence] = Field(default_factory=list)


# ============================================================
# 6. 完整画像
# ============================================================

class ProfileSnapshot(BaseModel):
    """完整人物偏好画像"""
    profile_version: str = "0.1.0"
    subject_id: str
    generated_at: datetime
    data_range: str  # "2024-01 至 2026-08"
    total_messages: int
    total_sessions: int

    preferences: List[Preference] = Field(default_factory=list)
    gift_signals: List[GiftSignal] = Field(default_factory=list)
    avoid: List[Preference] = Field(default_factory=list)  # 避雷清单
    behavior_patterns: List[BehaviorPattern] = Field(default_factory=list)
    unknowns: List[str] = Field(default_factory=list)  # 证据不足的疑问

    # 统计
    facts_extracted: int = 0
    preference_categories: int = 0
    highest_confidence: Optional[str] = None
    lowest_confidence: Optional[str] = None


class GiftRecommendation(BaseModel):
    """礼物推荐"""
    rank: int
    category: str
    item: str
    price_range: str
    confidence: float
    reasoning: str
    evidence_summary: List[str]
    risk_level: str  # low / medium / high
    risks: List[str] = Field(default_factory=list)
