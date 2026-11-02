#!/usr/bin/env python3
"""SSH 远程执行小工具（部署专用）

用法:
    python ssh_run.py "命令"
    python ssh_run.py            # 从 stdin 读命令

密码从环境变量 CLOUD_PWD 读取，避免出现在命令行历史里。
"""
import os
import sys

import paramiko

HOST = os.environ.get("CLOUD_HOST", "134.175.137.18")
PORT = int(os.environ.get("CLOUD_PORT", "22"))
USER = os.environ.get("CLOUD_USER", "ubuntu")
PWD = os.environ.get("CLOUD_PWD", "")

cmd = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    client.connect(
        hostname=HOST,
        port=PORT,
        username=USER,
        password=PWD,
        timeout=25,
        banner_timeout=30,
        auth_timeout=30,
        look_for_keys=False,
        allow_agent=False,
    )
except paramiko.AuthenticationException:
    print("[AUTH FAILED] 用户名或密码不对", file=sys.stderr)
    sys.exit(2)
except Exception as exc:  # noqa: BLE001
    print(f"[CONNECT FAILED] {type(exc).__name__}: {exc}", file=sys.stderr)
    sys.exit(3)

stdin, stdout, stderr = client.exec_command(cmd, timeout=1800)
out = stdout.read().decode("utf-8", "replace")
err = stderr.read().decode("utf-8", "replace")
code = stdout.channel.recv_exit_status()

if out:
    print(out, end="")
if err:
    print("[stderr]", err, end="")
print(f"\n[exit={code}]")
client.close()
sys.exit(0 if code == 0 else 1)
