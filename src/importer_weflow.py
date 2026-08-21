"""
Chat Profile Miner - WeFlow 微信聊天记录导入器
支持 WeFlow 导出的 JSON 格式。
"""
import json
from datetime import datetime
from typing import List
from schemas import Message, Platform, SpeakerRole, ContentType


class WeFlowImporter:
    """WeFlow 微信聊天记录导入器"""

    def __init__(self, target_name: str = None, self_name: str = None):
        self.target_name = target_name
        self.self_name = self_name

    def import_file(self, file_path: str) -> List[Message]:
        """导入 WeFlow JSON 文件"""
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # 获取会话信息
        session = data.get('session', {})
        if not self.target_name:
            self.target_name = session.get('displayName', session.get('nickname', '对方'))

        messages = []
        for msg_data in data.get('messages', []):
            msg = self._parse_message(msg_data, file_path)
            if msg:
                messages.append(msg)

        return messages

    def _parse_message(self, msg_data: dict, source_file: str) -> Message:
        """解析单条消息"""
        # 跳过非文本消息（动画表情、语音、图片等）
        msg_type = msg_data.get('type', '')
        content = msg_data.get('content', '')

        # 确定内容类型
        if msg_type == '文本消息':
            content_type = ContentType.TEXT
        elif msg_type == '动画表情':
            content_type = ContentType.STICKER
            content = '[表情包]'
        elif msg_type == '语音消息':
            content_type = ContentType.VOICE
            # 保留语音转文字内容
            if '[语音转文字]' in content:
                content = content.replace('[语音转文字]', '').strip()
            else:
                content = '[语音]'
        elif msg_type == '图片消息':
            content_type = ContentType.IMAGE
            content = '[图片]'
        elif msg_type == '视频消息':
            content_type = ContentType.VIDEO
            content = '[视频]'
        elif msg_type == '链接分享' or msg_type == '链接':
            content_type = ContentType.LINK
        elif msg_type == '文件消息':
            content_type = ContentType.FILE
            content = '[文件]'
        else:
            # 其他类型跳过
            return None

        # 跳过空内容
        if not content or content.strip() == '':
            return None

        # 判断发送者
        is_send = msg_data.get('isSend', 0)
        if is_send == 1:
            speaker = self.self_name or '我'
            speaker_role = SpeakerRole.SELF
        else:
            speaker = self.target_name
            speaker_role = SpeakerRole.TARGET

        # 解析时间
        time_str = msg_data.get('formattedTime', '')
        try:
            timestamp = datetime.strptime(time_str, '%Y-%m-%d %H:%M:%S')
        except ValueError:
            timestamp = datetime.now()

        return Message(
            message_id=str(msg_data.get('localId', '')),
            platform=Platform.WECHAT,
            conversation_id='default',
            timestamp=timestamp,
            speaker=speaker,
            speaker_role=speaker_role,
            content_type=content_type,
            text=content,
            source_file=source_file,
            source_offset=msg_data.get('localId', 0),
        )


def import_weflow(file_path: str, target_name: str = None, self_name: str = None) -> List[Message]:
    """便捷函数"""
    importer = WeFlowImporter(target_name, self_name)
    return importer.import_file(file_path)
