#!/usr/bin/env bash
# ContiNew Admin 生产部署脚本（在服务器上以 ubuntu 用户执行）
set -eu

APP=/opt/continew
PUBLIC_URL="http://134.175.137.18"
ASSETS=/home/ubuntu/deploy-assets

echo "===== 1. 创建目录 ====="
sudo mkdir -p "$APP"/{backend,web,data/mysql,data/redis,data/file,data/logs,data/nginx-logs}
sudo chown -R ubuntu:ubuntu "$APP"

echo "===== 2. 解包后端（bin/config/lib 必须保持同级）====="
mkdir -p "$APP/backend/app"
tar -xzf /home/ubuntu/backend.tgz -C "$APP/backend/app"
ls "$APP/backend/app"

echo "===== 3. 解包前端 ====="
tar -xzf /home/ubuntu/web.tgz -C "$APP/web"
ls "$APP/web" | head -5

echo "===== 4. 放置配置 ====="
cp "$ASSETS/Dockerfile" "$APP/backend/Dockerfile"
cp "$ASSETS/docker-compose.yml" "$APP/docker-compose.yml"
cp "$ASSETS/nginx.conf" "$APP/nginx.conf"

echo "===== 5. 生成密钥（仅首次）====="
ENVFILE="$APP/.env"
if [ ! -f "$ENVFILE" ]; then
  DB_PWD=$(openssl rand -hex 12)
  REDIS_PWD=$(openssl rand -hex 12)
  AES_KEY=$(openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c 32)
  JWT_SECRET=$(openssl rand -hex 32)
  REFRESH_SECRET=$(openssl rand -hex 32)

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/priv.pem 2>/dev/null
  PRIV=$(openssl pkcs8 -topk8 -nocrypt -in /tmp/priv.pem -outform DER | base64 -w0)
  PUB=$(openssl rsa -in /tmp/priv.pem -pubout -outform DER | base64 -w0)
  rm -f /tmp/priv.pem

  {
    echo "DB_PWD=$DB_PWD"
    echo "REDIS_PWD=$REDIS_PWD"
    echo "ACCESS_TOKEN_JWT_SECRET=$JWT_SECRET"
    echo "REFRESH_TOKEN_SECRET=$REFRESH_SECRET"
    echo "FIELD_ENCRYPT_PASSWORD=$AES_KEY"
    echo "FIELD_ENCRYPT_PUBLIC_KEY=$PUB"
    echo "FIELD_ENCRYPT_PRIVATE_KEY=$PRIV"
    echo "PUBLIC_URL=$PUBLIC_URL"
  } > "$ENVFILE"
  chmod 600 "$ENVFILE"
  echo "已生成 .env（包含 7 项随机密钥），权限 600"
else
  echo ".env 已存在，跳过生成"
fi

echo "===== 6. 构建并启动 ====="
cd "$APP"
sudo docker compose up -d --build 2>&1 | tail -25

echo "===== 7. 容器状态 ====="
sudo docker compose ps
