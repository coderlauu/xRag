# Deployment Baseline

本目录提供 `v1 / Phase 1A` 的最小可执行 CD 基线：

- GitHub Actions 构建并推送 `api / worker / web` 镜像到 `GHCR`
- 通过 `SSH + docker compose` 在远端主机部署 `staging / production`
- 部署后执行 HTTP smoke 验证

## Required Remote Host

- Linux 主机
- Docker Engine + Docker Compose
- 可访问你的镜像仓库地址，例如阿里云 ACR
- 对外放行 `80` 和 `443`

## Recommended Layout

- 部署目录建议固定为 `/srv/xrag`
- 推荐使用独立的非 root 用户，例如 `deploy`
- 该用户需要具备：
  - SSH 登录能力
  - 对 `/srv/xrag` 的读写权限
  - Docker 执行权限

如果你不想把 `deploy` 用户加入 `docker` 组，也可以给它配置免密 sudo 用于 Docker：

```bash
echo 'deploy ALL=(ALL) NOPASSWD: /usr/bin/docker, /usr/bin/docker compose' | sudo tee /etc/sudoers.d/xrag-deploy
sudo chmod 440 /etc/sudoers.d/xrag-deploy
```

示例：

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /srv/xrag
sudo chown -R deploy:deploy /srv/xrag
```

## SSH Setup

GitHub Actions 部署依赖一对 SSH 密钥。

1. 在本机生成专用部署密钥：

```bash
ssh-keygen -t ed25519 -C "xrag-github-actions" -f ~/.ssh/xrag_github_actions
```

2. 将公钥追加到远端服务器：

```bash
ssh-copy-id -i ~/.ssh/xrag_github_actions.pub deploy@YOUR_SERVER_IP
```

如果没有 `ssh-copy-id`，也可以手动追加到远端 `~/.ssh/authorized_keys`。

3. 将私钥内容保存为 GitHub environment secret `SSH_PRIVATE_KEY`：

```bash
cat ~/.ssh/xrag_github_actions
```

4. 对应 secret 推荐值：

- `SSH_HOST=YOUR_SERVER_IP`
- `SSH_PORT=22`
- `SSH_USER=deploy`
- `DEPLOY_PATH=/srv/xrag`

5. 在启用 GitHub Actions 前，先在本机做一次真实登录验证：

```bash
ssh -i ~/.ssh/xrag_github_actions deploy@YOUR_SERVER_IP
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

- `DEPLOY_ENV_FILE` 为完整多行 `.env` 内容，可参考 [staging.env.example](/Users/coderlauu/xRag/deploy/env/staging.env.example) 和 [production.env.example](/Users/coderlauu/xRag/deploy/env/production.env.example)
- `APP_BASE_URL` 为部署后的 Web 外部访问地址，例如 `https://staging.example.com`
- `REGISTRY_HOST` 例如 `crpi-9zaebevr54ofetmt.cn-guangzhou.personal.cr.aliyuncs.com`
- `REGISTRY_NAMESPACE` 例如 `coderlau`
- `REGISTRY_USERNAME` 例如 `coderlau`
- `REGISTRY_PASSWORD` 为镜像仓库登录密码或 token
- `DEPLOY_ENV_FILE` 中还应包含 `STORAGE_PUBLIC_HOST`、`STORAGE_PUBLIC_URL`、`CONSOLE_PUBLIC_HOST`、`CONSOLE_PUBLIC_URL`、`DB_CONSOLE_PUBLIC_HOST`
- 如果你希望像 `console.xrag.coderlau.cn` 一样通过浏览器输入账号密码后直接进入数据库管理台，还应包含：
  - `DB_CONSOLE_BASICAUTH_USERNAME`
  - `DB_CONSOLE_BASICAUTH_PASSWORD_HASH`

## Suggested Values For Current Project

如果当前只先接一台服务器，建议先把 `production` 接通：

- `SSH_HOST=8.134.122.242`
- `SSH_PORT=22`
- `SSH_USER=deploy`
- `DEPLOY_PATH=/srv/xrag`
- `APP_BASE_URL=https://xrag.coderlau.cn`
- `APP_DOMAIN=xrag.coderlau.cn`
- `STORAGE_PUBLIC_HOST=storage.xrag.coderlau.cn`
- `STORAGE_PUBLIC_URL=https://storage.xrag.coderlau.cn`
- `CONSOLE_PUBLIC_HOST=console.xrag.coderlau.cn`
- `CONSOLE_PUBLIC_URL=https://console.xrag.coderlau.cn`
- `DB_CONSOLE_PUBLIC_HOST=db.xrag.coderlau.cn`
- `DB_CONSOLE_BASICAUTH_USERNAME=admin`
- `DB_CONSOLE_BASICAUTH_PASSWORD_HASH=<bcrypt-hash>`

`staging` 有两种做法：

- 推荐：增加 `staging.xrag.coderlau.cn`
- 临时：先用同一台机器的公网 IP 和一个非 80/443 端口做 smoke

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

- 入口建议使用：`https://db.xrag.coderlau.cn`
- 通过 `Caddy basic auth` 进行第一层访问控制
- 通过 `pgweb` 直接连接内部 `postgres`，打开页面后无需再次填写数据库连接串

推荐在 `DEPLOY_ENV_FILE` 中加入：

```env
DB_CONSOLE_PUBLIC_HOST=db.xrag.coderlau.cn
DB_CONSOLE_BASICAUTH_USERNAME=admin
DB_CONSOLE_BASICAUTH_PASSWORD_HASH=$$2y$$10$$mQhXgmqA0c/66a0ixsA9iOL02USstip1ffXy./tzGDp7TJfknHnua
```

上面的默认哈希对应口令：

```text
change-me
```

更稳的做法是生成你自己的 bcrypt 哈希后再替换。

数据库 Web 管理台启用后，访问方式是：

1. 浏览器打开 `https://db.xrag.coderlau.cn`
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
  - `Database`: `xrag`
  - `Username`: `xrag`
  - `Password`: 生产环境中的 `POSTGRES_PASSWORD`
- SSH
  - `SSH Host`: `8.134.122.242`
  - `SSH Port`: `22`
  - `SSH User`: 你的实际 SSH 登录用户
  - 认证方式：私钥优先

如果你想先在终端里自测，也可以在本机执行：

```bash
ssh -N -L 5432:127.0.0.1:5432 root@8.134.122.242
```

然后再让客户端连接：

- `Host=127.0.0.1`
- `Port=5432`
- `Database=xrag`

## Local Validation

本仓库已经通过本地容器化验证：

- `api / worker / web / postgres / redis / minio` 可通过 `deploy/compose/stack.compose.yml` 拉起
- `/api/v1/health` 返回 `{"status":"ok"}`
- 通过外部入口成功完成了 `create text document -> list -> detail` 的最小业务闭环

## Inspection And Debugging

如果你需要查看 production 当前跑了什么、数据库里有什么、MinIO 里有什么，直接看：

- [Production Inspection Guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)

## Deploy Flow

1. `main` push 先通过 CI
2. CI 后续 job 构建并推送镜像
3. 自动部署 `staging`
4. 对 `staging` 执行 smoke
5. `staging` 通过后自动部署 `production`
6. 对 `production` 执行 smoke
