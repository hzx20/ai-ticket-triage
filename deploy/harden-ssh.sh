#!/bin/bash
# SSH 安全加固：关闭密码认证，只允许密钥登录
# 2026-11-03
# 说明：Ubuntu 24.04 的 /etc/ssh/sshd_config 顶部有 Include /etc/ssh/sshd_config.d/*.conf，
#      sshd 按文件名排序读取，且「第一个出现的值生效」。所以 00- 开头的文件优先于 50-cloud-init.conf。
#      两处都改，是为了防止 cloud-init 重新生成 50 文件时把密码认证又打开。

set -e

SUFFIX=".bak-20261103-1628"

echo "=== 1. 备份 ==="
cp -a /etc/ssh/sshd_config "/etc/ssh/sshd_config${SUFFIX}"
cp -a /etc/ssh/sshd_config.d/50-cloud-init.conf "/etc/ssh/sshd_config.d/50-cloud-init.conf${SUFFIX}"
ls -la /etc/ssh/sshd_config${SUFFIX} /etc/ssh/sshd_config.d/50-cloud-init.conf${SUFFIX}

echo "=== 2. 写入 00-hardening.conf（优先级最高）==="
cat > /etc/ssh/sshd_config.d/00-hardening.conf <<'CONF'
# 2026-11-03 安全加固：只允许密钥登录
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
CONF
chmod 600 /etc/ssh/sshd_config.d/00-hardening.conf
cat /etc/ssh/sshd_config.d/00-hardening.conf

echo "=== 3. 同步修改原有配置 ==="
sed -i 's/^PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config.d/50-cloud-init.conf
sed -i 's/^PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^PermitRootLogin yes/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config

echo "=== 4. 语法检查 ==="
if sshd -t; then
    echo "SYNTAX_OK"
else
    echo "SYNTAX_ERROR 配置有问题，请勿重载"
    exit 1
fi

echo "=== 5. 重载后的生效值 ==="
sshd -T | grep -E '^passwordauthentication|^permitrootlogin|^pubkeyauthentication'

echo "=== 完成，尚未重载服务 ==="
