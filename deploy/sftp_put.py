#!/usr/bin/env python3
"""SFTP 上传工具（部署专用，分块写入带进度）

用法: python sftp_put.py <本地文件> <远程绝对路径>
密码从环境变量 CLOUD_PWD 读取。
"""
import os
import sys
import time

import paramiko

HOST = os.environ.get("CLOUD_HOST", "134.175.137.18")
PORT = int(os.environ.get("CLOUD_PORT", "22"))
USER = os.environ.get("CLOUD_USER", "ubuntu")
PWD = os.environ.get("CLOUD_PWD", "")

local = sys.argv[1]
remote = sys.argv[2]

size = os.path.getsize(local)
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(
    hostname=HOST, port=PORT, username=USER, password=PWD,
    timeout=25, banner_timeout=30, auth_timeout=30,
    look_for_keys=False, allow_agent=False,
)

sftp = client.open_sftp()
start = time.time()
sent = 0
next_report = 10
CHUNK = 512 * 1024

with open(local, "rb") as src, sftp.open(remote, "wb") as dst:
    dst.set_pipelined(True)
    while True:
        buf = src.read(CHUNK)
        if not buf:
            break
        dst.write(buf)
        sent += len(buf)
        pct = sent * 100 // size
        if pct >= next_report:
            elapsed = max(time.time() - start, 0.001)
            print(f"  {pct}%  {sent/1024/1024:.1f}/{size/1024/1024:.1f} MB  "
                  f"{sent/1024/1024/elapsed:.2f} MB/s", flush=True)
            next_report = pct - (pct % 10) + 10

elapsed = time.time() - start
remote_size = sftp.stat(remote).st_size
print(f"完成: {remote}  本地 {size/1024/1024:.1f} MB  远程 {remote_size/1024/1024:.1f} MB  "
      f"用时 {elapsed:.0f}s  平均 {size/1024/1024/max(elapsed,0.001):.2f} MB/s")
if remote_size != size:
    print(f"[警告] 大小不一致！")
sftp.close()
client.close()
