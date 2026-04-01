# Deployment Baseline

本目录提供 `v1 / Phase 1A` 的最小可执行 CD 基线：

- GitHub Actions 构建并推送 `api / worker / web` 镜像到 `GHCR`
- 通过 `SSH + docker compose` 在远端主机部署 `staging / production`
- 部署后执行 HTTP smoke 验证

## Required Remote Host

- Linux 主机
- Docker Engine + Docker Compose
- 可访问 `ghcr.io`

## Recommended Layout

- 部署目录建议固定为 `/srv/xrag`
- 推荐使用独立的非 root 用户，例如 `deploy`
- 该用户需要具备：
  - SSH 登录能力
  - 对 `/srv/xrag` 的读写权限
  - Docker 执行权限

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

## Required GitHub Environment Secrets

对 `staging` 和 `production` 两个 environment 分别配置：

- `SSH_HOST`
- `SSH_PORT`
- `SSH_USER`
- `SSH_PRIVATE_KEY`
- `DEPLOY_PATH`
- `DEPLOY_ENV_FILE`
- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `APP_BASE_URL`

说明：

- `DEPLOY_ENV_FILE` 为完整多行 `.env` 内容，可参考 [staging.env.example](/Users/coderlauu/xRag/deploy/env/staging.env.example) 和 [production.env.example](/Users/coderlauu/xRag/deploy/env/production.env.example)
- `APP_BASE_URL` 为部署后的 Web 外部访问地址，例如 `https://staging.example.com`
- `GHCR_TOKEN` 需要具备镜像拉取权限
- `GHCR_USERNAME` 通常为 GitHub 用户名
- `GHCR_TOKEN` 可使用 PAT，至少具备 `read:packages`

## Suggested Values For Current Project

如果当前只先接一台服务器，建议先把 `production` 接通：

- `SSH_HOST=8.134.122.242`
- `SSH_PORT=22`
- `SSH_USER=deploy`
- `DEPLOY_PATH=/srv/xrag`
- `APP_BASE_URL=https://xrag.coderlau.cn`

`staging` 有两种做法：

- 推荐：增加 `staging.xrag.coderlau.cn`
- 临时：先用同一台机器的公网 IP 和一个非 80/443 端口做 smoke

## Local Validation

本仓库已经通过本地容器化验证：

- `api / worker / web / postgres / redis / minio` 可通过 `deploy/compose/stack.compose.yml` 拉起
- `/api/v1/health` 返回 `{"status":"ok"}`
- 通过外部入口成功完成了 `create text document -> list -> detail` 的最小业务闭环

## Deploy Flow

1. `main` push 先通过 CI
2. CI 后续 job 构建并推送镜像
3. 自动部署 `staging`
4. 对 `staging` 执行 smoke
5. `staging` 通过后自动部署 `production`
6. 对 `production` 执行 smoke
