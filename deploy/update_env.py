#!/usr/bin/env python3
"""把新生成的 RSA 密钥对写入 /opt/continew/.env（只替换两行，其余键值保持不变）"""
import pathlib

env = pathlib.Path("/opt/continew/.env")
assets = pathlib.Path("/home/ubuntu/deploy-assets")

pub = (assets / "field_pub.b64").read_text().strip()
priv = (assets / "field_priv.b64").read_text().strip()

lines = env.read_text().splitlines()
out = []
hit_pub = hit_priv = False
for ln in lines:
    if ln.startswith("FIELD_ENCRYPT_PUBLIC_KEY="):
        out.append("FIELD_ENCRYPT_PUBLIC_KEY=" + pub)
        hit_pub = True
    elif ln.startswith("FIELD_ENCRYPT_PRIVATE_KEY="):
        out.append("FIELD_ENCRYPT_PRIVATE_KEY=" + priv)
        hit_priv = True
    else:
        out.append(ln)

env.write_text("\n".join(out) + "\n")
print(f"公钥写入: {hit_pub} (长度 {len(pub)})")
print(f"私钥写入: {hit_priv} (长度 {len(priv)})")
print(f".env 总行数: {len(out)}")
