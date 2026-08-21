"""
小规模 LLM 测试 - 只分析几个会话
"""
import sys
import os
import json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from importer_weflow import import_weflow
from extractor_llm import LLMExtractor, SessionSplitter

def main():
    data_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data', 'chuniao.json')
    print('Importing...')
    messages = import_weflow(data_path, target_name='春杪', self_name='未然')

    text_msgs = [m for m in messages if m.content_type.value == 'text']
    print(f'Text messages: {len(text_msgs)}')

    # Split sessions
    splitter = SessionSplitter(gap_hours=2.0)
    sessions = splitter.split(text_msgs)
    print(f'Total sessions: {len(sessions)}')

    # Find a session with interesting content
    for i, session in enumerate(sessions[:100]):
        target_msgs = [m for m in session if m.speaker_role.value == 'target']
        if len(session) >= 10 and len(target_msgs) >= 4:
            print(f'\nSession {i} ({len(session)} msgs, {len(target_msgs)} from target):')
            for msg in session[:8]:
                print(f'  [{msg.timestamp.strftime("%m-%d %H:%M")}] {msg.speaker}: {msg.text[:60]}')
            if len(session) > 8:
                print(f'  ... +{len(session)-8} more')

            # Test LLM
            print('\nCalling LLM...')
            llm = LLMExtractor(
                model_name='deepseek-v4-flash',
                api_base='https://api.deepseek.com',
                api_key='***'
            )
            result = llm.extract_from_session(session, '春杪')
            print(f'\nResult:')
            print(json.dumps(result, ensure_ascii=False, indent=2))
            break

if __name__ == '__main__':
    main()

