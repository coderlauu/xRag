# Deployment Baseline

本目录提供一套最小可执行 CD 基线：

- GitHub Actions 构建并推送 `api / worker / web` 镜像到 `GHCR` 或其他镜像仓库
- 通过 `SSH + docker compose` 在远端主机部署 `staging / production`
- 部署后执行 HTTP smoke 验证

## Required Remote Host

- Linux 主机
- Docker Engine + Docker Compose
- 可访问你的镜像仓库地址
- 对外放行 `80` 和 `443`

## Recommended Layout

- 部署目录建议固定为 `/srv/app`
- 推荐使用独立的非 root 用户，例如 `deploy`
- 该用户需要具备：
  - SSH 登录能力
  - 对 `/srv/app` 的读写权限
  - Docker 执行权限

如果你不想把 `deploy` 用户加入 `docker` 组，也可以给它配置免密 sudo 用于 Docker：

```bash
echo 'deploy ALL=(ALL) NOPASSWD: /usr/bin/docker, /usr/bin/docker compose' | sudo tee /etc/sudoers.d/app-deploy
sudo chmod 440 /etc/sudoers.d/app-deploy
```

示例：

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /srv/app
sudo chown -R deploy:deploy /srv/app
```

## SSH Setup

GitHub Actions 部署依赖一对 SSH 密钥。

1. 在本机生成专用部署密钥：

```bash
ssh-keygen -t ed25519 -C "app-github-actions" -f ~/.ssh/app_github_actions
```

2. 将公钥追加到远端服务器：

```bash
ssh-copy-id -i ~/.ssh/app_github_actions.pub deploy@YOUR_SERVER_IP
```

如果没有 `ssh-copy-id`，也可以手动追加到远端 `~/.ssh/authorized_keys`。

3. 将私钥内容保存为 GitHub environment secret `SSH_PRIVATE_KEY`：

```bash
cat ~/.ssh/app_github_actions
```

4. 对应 secret 推荐值：

- `SSH_HOST=YOUR_SERVER_IP`
- `SSH_PORT=22`
- `SSH_USER=deploy`
- `DEPLOY_PATH=/srv/app`

5. 在启用 GitHub Actions 前，先在本机做一次真实登录验证：

```bash
ssh -i ~/.ssh/app_github_actions deploy@YOUR_SERVER_IP
```

如果这一步都不能成功，GitHub Actions 一定也会失败。

## Required GitHub Environment Secrets

对 `staging` 和 `production` 两个 environment 分别配置：

- `SSH_HOST`
- `SSH_PORT`
- `SSH_USER`
- `SSH_PRIVATE_KEY`
- `DEPLOY_PATH`
- `DEPLOY_ENV_FILE`
- `REGISTRY_HOST`
- `REGISTRY_NAMESPACE`
- `REGISTRY_USERNAME`
- `REGISTRY_PASSWORD`
- `APP_BASE_URL`

说明：

- `DEPLOY_ENV_FILE` 为完整多行 `.env` 内容，可参考 [staging.env.example](env/staging.env.example) 和 [production.env.example](env/production.env.example)
- `APP_BASE_URL` 为部署后的 Web 外部访问地址，例如 `https://staging.example.com`
- `REGISTRY_HOST` 为你的镜像仓库地址，例如 `your-registry.example.com`
- `REGISTRY_NAMESPACE` / `REGISTRY_USERNAME` 为你的镜像仓库命名空间与账号
- `REGISTRY_PASSWORD` 为镜像仓库登录密码或 token
- `DEPLOY_ENV_FILE` 中还应包含 `STORAGE_PUBLIC_HOST`、`STORAGE_PUBLIC_URL`、`CONSOLE_PUBLIC_HOST`、`CONSOLE_PUBLIC_URL`、`DB_CONSOLE_PUBLIC_HOST`
- 如果你希望通过浏览器输入账号密码后直接进入数据库管理台，还应包含：
  - `DB_CONSOLE_BASICAUTH_USERNAME`
  - `DB_CONSOLE_BASICAUTH_PASSWORD_HASH`

## HTTPS Termination

当前生产基线使用 `Caddy` 做外层反向代理和自动 TLS：

- `80/443` 由 `Caddy` 监听
- `Caddy` 自动为 `APP_DOMAIN` 申请和续期证书
- `Caddy` 同时为 `STORAGE_PUBLIC_HOST` 代理对象存储上传 API
- `Caddy` 为 `CONSOLE_PUBLIC_HOST` 代理 MinIO Console
- `Caddy` 为 `DB_CONSOLE_PUBLIC_HOST` 代理数据库 Web 管理台
- `web` 仅在内网暴露 `8080`

前提：

- `APP_DOMAIN` 已解析到服务器公网 IP
- `STORAGE_PUBLIC_HOST` 已解析到服务器公网 IP
- `CONSOLE_PUBLIC_HOST` 已解析到服务器公网 IP
- `DB_CONSOLE_PUBLIC_HOST` 已解析到服务器公网 IP
- 服务器安全组和系统防火墙已放行 `80/443`
- 80 端口未被其他 Web 服务占用

## Database Web Console

当前生产栈可选接入一个受控数据库 Web 管理台：

- 入口建议使用你自己的 `DB_CONSOLE_PUBLIC_HOST` 域名
- 通过 `Caddy basic auth` 进行第一层访问控制
- 通过 `pgweb` 直接连接内部 `postgres`，打开页面后无需再次填写数据库连接串

推荐在 `DEPLOY_ENV_FILE` 中加入：

```env
DB_CONSOLE_PUBLIC_HOST=db.app.example.com
DB_CONSOLE_BASICAUTH_USERNAME=admin
DB_CONSOLE_BASICAUTH_PASSWORD_HASH=$$2y$$10$$mQhXgmqA0c/66a0ixsA9iOL02USstip1ffXy./tzGDp7TJfknHnua
```

上面的默认哈希对应口令：

```text
change-me
```

更稳的做法是生成你自己的 bcrypt 哈希后再替换。

数据库 Web 管理台启用后，访问方式是：

1. 浏览器打开你配置的 `DB_CONSOLE_PUBLIC_HOST`
2. 先输入 `basic auth` 账号密码
3. 认证通过后直接进入数据库管理台

## Navicat / 桌面客户端访问

如果你希望继续使用 `Navicat`、`TablePlus`、`DBeaver` 这类桌面客户端，推荐走 `SSH` 隧道，而不是直接暴露数据库公网端口。

当前部署会把 PostgreSQL 只绑定到服务器本机回环地址：

```text
127.0.0.1:5432
```

所以访问方式应为：

1. 在客户端启用 `SSH Tunnel`
2. `SSH Host` 指向你的服务器
3. PostgreSQL 常规连接指向服务器本机回环地址

推荐参数：

- 常规
  - `Host`: `127.0.0.1`
  - `Port`: `5432`
  - `Database`: 你在 `POSTGRES_DB` 中配置的值
  - `Username`: 你在 `POSTGRES_USER` 中配置的值
  - `Password`: 生产环境中的 `POSTGRES_PASSWORD`
- SSH
  - `SSH Host`: 你的服务器地址
  - `SSH Port`: `22`
  - `SSH User`: 你的实际 SSH 登录用户
  - 认证方式：私钥优先

如果你想先在终端里自测，也可以在本机执行：

```bash
ssh -N -L 5432:127.0.0.1:5432 root@YOUR_SERVER_IP
```

然后再让客户端连接：

- `Host=127.0.0.1`
- `Port=5432`

## Disk Guard

为了避免 deploy 因磁盘打满而写入失败，production 现在补了两层守卫：

1. `deploy-production` 上传 bundle 前，GitHub Actions 会先通过 SSH 执行一次远端磁盘守护脚本
2. 远端真正 rollout 前，`remote-deploy.sh` 会再执行一次磁盘守护脚本

守护脚本位置：

- `deploy/scripts/disk-guard.sh`

它只会清理可再生资产：

- `${DEPLOY_PATH}/shared/tmp`
- 旧 `release` 目录，仅保留最近 `APP_KEEP_RELEASES` 个
- stopped containers
- 无用镜像
- build cache
- 过大的 Docker JSON 日志

它不会触碰：

- PostgreSQL 数据卷
- MinIO 数据卷
- 任何 `docker volume prune`

默认阈值可写入 `DEPLOY_ENV_FILE`：

```env
APP_DISK_WARN_PERCENT=70
APP_DISK_PRUNE_PERCENT=80
APP_DISK_FAIL_PERCENT=95
APP_KEEP_RELEASES=5
APP_DOCKER_LOG_TRUNCATE_MB=200
```

手工执行：

```bash
/srv/app/shared/bin/app-disk-guard.sh /srv/app
```

### 定时执行

仓库已提供 `systemd` 资产：

- `deploy/systemd/app-disk-guard.service`
- `deploy/systemd/app-disk-guard.timer`

每次 deploy 会把它们同步到：

- `/srv/app/shared/systemd`

在服务器上启用：

```bash
sudo cp /srv/app/shared/systemd/app-disk-guard.service /etc/systemd/system/
sudo cp /srv/app/shared/systemd/app-disk-guard.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now app-disk-guard.timer
sudo systemctl status app-disk-guard.timer
```

查看最近执行记录：

```bash
journalctl -u app-disk-guard.service -n 100 --no-pager
```

## Local Validation

本地可通过以下方式验证部署基线：

- `api / worker / web / postgres / redis / minio` 可通过 `deploy/compose/stack.compose.yml` 拉起
- `/api/v1/health` 返回 `{"status":"ok"}`

## Deploy Flow

1. `main` push 先通过 CI
2. CI 后续 job 构建并推送镜像
3. 自动部署 `staging`
4. 对 `staging` 执行 smoke
5. `staging` 通过后自动部署 `production`
6. 对 `production` 执行 smoke

如果 `deploy-production` 前磁盘使用率经过清理后仍高于 `APP_DISK_FAIL_PERCENT`，workflow 会直接失败并要求先人工处理主机空间，而不会继续假成功。
