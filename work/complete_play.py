# -*- coding: utf-8 -*-
"""完整跑通剧本杀：SETUP→INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED（含 D7 审批门）"""
import json, subprocess, sys, time, urllib.request

BASE = 'http://localhost:8000'

def post(path, body=None):
    req = urllib.request.Request(BASE + path, method='POST')
    req.add_header('Content-Type', 'application/json')
    data = json.dumps(body or {}).encode('utf-8')
    try:
        with urllib.request.urlopen(req, data, timeout=120) as r:
            return json.loads(r.read().decode('utf-8'))
    except Exception as e:
        return {'error': str(e), 'path': path}

def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=15) as r:
            return json.loads(r.read().decode('utf-8'))
    except Exception as e:
        return {'error': str(e)}

def wait_phase(target, seconds, player='沈墨'):
    t0 = time.time()
    while time.time() - t0 < seconds:
        time.sleep(4)
        st = get('/api/script/status?player=' + urllib.parse.quote(player))
        if st and st.get('phase') == target:
            return st
    return st

# 1) 新建对局（5 名玩家，含人类 沈墨）
init = post('/api/script/init', {'theme': '深空站沉默事件', 'players': ['沈墨','林晚秋','顾云舟','苏浅浅','陈一鸣'], 'mode': 'full'})
sid = init.get('session_id')
print('INIT phase=%s sid=%s' % (init.get('phase'), sid))
if not sid:
    raise SystemExit('init failed: %s' % init)

# 2) 生成完整剧本 → 等搜证阶段
post('/api/script/generate_full', {})
st = wait_phase('investigation', 200)
print('PHASE after gen_full:', st.get('phase'))
if st.get('phase') != 'investigation':
    raise SystemExit('not investigation: %s' % st)

# 3) 进讨论 → 等投票
d = post('/api/script/start_discussion', {})
print('DISCUSSION:', d.get('phase'))
st = wait_phase('vote', 120)
print('PHASE after discussion:', st.get('phase'))
if st.get('phase') != 'vote':
    raise SystemExit('not vote: %s' % st)

# 4) 全部投票（决定性：4 票 → 林晚秋，1 票 → 沈墨）
votes = [
    ('沈墨', '林晚秋'), ('顾云舟', '林晚秋'), ('苏浅浅', '林晚秋'),
    ('陈一鸣', '林晚秋'), ('林晚秋', '沈墨'),
]
for p, s in votes:
    r = post('/api/script/vote', {'player': p, 'suspect': s})
    print('VOTE %s->%s: %s' % (p, s, r.get('result')))
vs = get('/api/script/vote/status?player=')
print('VOTE STATUS: %s/%s' % (vs.get('voted'), vs.get('total')))

# 5) resolve（后台阻塞等审批）→ 3s 后 approve → 等 reveal
proc = subprocess.Popen(
    [sys.executable, '-c',
     "import json,urllib.request;req=urllib.request.Request('http://localhost:8000/api/script/resolve',method='POST');req.add_header('Content-Type','application/json');print(json.loads(urllib.request.urlopen(req,b'{}',timeout=90).read().decode('utf-8')))"],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(3)
ap = post('/api/approval/approve', {'session_id': sid})
print('APPROVE:', json.dumps(ap, ensure_ascii=False))
out, err = proc.communicate(timeout=100)
print('RESOLVE OUT:', out.decode('utf-8', 'replace').strip())
if err:
    print('RESOLVE ERR:', err.decode('utf-8', 'replace').strip()[:300])

# 6) finish → ended
fin = post('/api/script/finish', {})
print('FINISH:', json.dumps(fin, ensure_ascii=False)[:200])
time.sleep(2)
final = get('/api/script/status?player=' + urllib.parse.quote('沈墨'))
print('FINAL phase=%s game_over=%s winner=%s' % (final.get('phase'), final.get('game_over'), final.get('winner')))
print('TRUTH:', (final.get('truth') or '')[:120])
if final.get('phase') == 'ended':
    with open(r'D:\roleplay-java\work\full_play\final-ended.json', 'w', encoding='utf-8') as f:
        json.dump(final, f, ensure_ascii=False, indent=2)
    print('COMPLETED')
else:
    print('NOT ENDED (phase=%s)' % final.get('phase'))
