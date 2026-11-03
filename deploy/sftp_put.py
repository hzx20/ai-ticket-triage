#!/usr/bin/env python3
"""文件上传工具（部署专用，分块写入带进度）

用法: python sftp_put.py <本地文件> <远程绝对路径>

认证：优先用 ~/.ssh/id_ed25519 密钥（服务器 2026-11-03 起已关闭密码认证），
      密钥不存在时才回退到环境变量 CLOUD_PWD。

踩过的两个坑（都别再踩）：
1. paramiko 的 sftp.put() 在本机（paramiko 5.1.0 + Windows）会抛
   FileNotFoundError，因为内部 putfo 走的是 self.file() 这条兼容路径。
   自己用 sftp.open() + write() 就没问题。
2. sftp.open() 之后如果调 dst.set_pipelined(True)，传大文件同样会抛
   FileNotFoundError。去掉它，用普通同步写，慢一点但稳。
3. 想用系统 scp 的话要小心：Git Bash 的 MSYS 会把 ubuntu@host:/home/xxx
   里的 /home/xxx 改写成 D:/.../PortableGit/home/xxx，必须双斜杠或设
   MSYS2_ARG_CONV_EXCL="*"。
"""
import os
import sys
import time

import paramiko

HOST = os.environ.get("CLOUD_HOST", "134.175.137.18")
PORT = int(os.environ.get("CLOUD_PORT", "22"))
USER = os.environ.get("CLOUD_USER", "ubuntu")
PWD = os.environ.get("CLOUD_PWD", "")
KEY_PATH = os.path.expanduser(os.environ.get("CLOUD_KEY", "~/.ssh/id_ed25519"))

CHUNK = 512 * 1024

# Git Bash（MSYS）在把参数交给 Windows 程序时，会把看起来像绝对路径的参数
# /home/ubuntu/x 改写成本地路径 D:/.../PortableGit/home/ubuntu/x。
# 这个改写发生在启动 python.exe 之前，脚本拿到的 sys.argv 已经是错的。
# 所以这里做一次还原。实测：传 176M 包时报 FileNotFoundError 的真正原因就是这个，
# 跟 paramiko 无关。
MSYS_MARKER = "/PortableGit/"


def normalize_remote(path):
    """把 MSYS 改写过的远程路径还原成 /home/... 形式"""
    idx = path.find(MSYS_MARKER)
    if idx != -1:
        rest = path[idx + len(MSYS_MARKER):]
        return "/" + rest.replace("\\", "/")
    return path


def connect():
    """建立 SSH 连接：有密钥用密钥，没有才用密码"""
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    params = dict(
        hostname=HOST,
        port=PORT,
        username=USER,
        timeout=25,
        banner_timeout=30,
        auth_timeout=30,
        look_for_keys=False,
        allow_agent=False,
    )
    if os.path.exists(KEY_PATH):
        params["key_filename"] = KEY_PATH
    else:
        params["password"] = PWD
    client.connect(**params)
    return client


def main():
    if len(sys.argv) < 3:
        print("用法: python sftp_put.py <本地文件> <远程绝对路径>", file=sys.stderr)
        sys.exit(1)

    local, remote = sys.argv[1], normalize_remote(sys.argv[2])
    if not os.path.exists(local):
        print(f"[错误] 本地文件不存在: {local}", file=sys.stderr)
        sys.exit(1)

    size = os.path.getsize(local)
    print(f"上传 {local} ({size / 1048576:.1f} MB) → {USER}@{HOST}:{remote}", flush=True)

    client = connect()
    sftp = client.open_sftp()
    start = time.time()
    sent = 0
    next_report = 10

    with open(local, "rb") as src, sftp.open(remote, "wb") as dst:
        while True:
            buf = src.read(CHUNK)
            if not buf:
                break
            dst.write(buf)
            sent += len(buf)
            pct = sent * 100 // size
            if pct >= next_report:
                elapsed = max(time.time() - start, 0.001)
                print(f"  {pct}%  {sent / 1048576:.1f}/{size / 1048576:.1f} MB  "
                      f"{sent / 1048576 / elapsed:.2f} MB/s", flush=True)
                next_report = pct - (pct % 10) + 10

    remote_size = sftp.stat(remote).st_size
    elapsed = time.time() - start
    print(f"完成: 本地 {size / 1048576:.1f} MB  远程 {remote_size / 1048576:.1f} MB  "
          f"用时 {elapsed:.0f}s")
    if remote_size != size:
        print("[警告] 大小不一致！")
    sftp.close()
    client.close()


if __name__ == "__main__":
    main()
