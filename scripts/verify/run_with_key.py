#!/usr/bin/env python3
"""启动包装器：从 Windows 用户环境注册表读取 ROLEPLAY_LLM_API_KEY 注入子进程 env，
再 java -jar 启动 roleplay 后端。避免 key 出现在命令行/进程列表/日志。
用法: python scripts/verify/run_with_key.py [extra-java-args...]
"""
import os, subprocess, sys, pathlib

KEY_NAME = "ROLEPLAY_LLM_API_KEY"

def get_user_env_var(name):
    """从 Windows 用户环境注册表读取变量（setx 只对新进程生效，当前进程读不到）"""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Environment") as key:
            val, _ = winreg.QueryValueEx(key, name)
            return val
    except Exception as e:
        print(f"[run_with_key] winreg read failed: {e}", file=sys.stderr)
        return None

def main():
    project = pathlib.Path(__file__).resolve().parents[2]
    jar = project / "target" / "roleplay-engine-1.0.0-SNAPSHOT.jar"
    if not jar.exists():
        print(f"[run_with_key] jar not found: {jar}", file=sys.stderr)
        return 1

    key = get_user_env_var(KEY_NAME)
    if not key:
        print(f"[run_with_key] {KEY_NAME} NOT FOUND in user env!", file=sys.stderr)
        return 2

    env = os.environ.copy()
    env[KEY_NAME] = key
    print(f"[run_with_key] key loaded from user env: len={len(key)} prefix={key[:6]}... suffix={key[-4:]}")

    cmd = ["java", "-jar", str(jar)] + sys.argv[1:]
    print(f"[run_with_key] starting: java -jar {jar.name} {' '.join(sys.argv[1:])}")
    return subprocess.call(cmd, env=env)

if __name__ == "__main__":
    sys.exit(main())
