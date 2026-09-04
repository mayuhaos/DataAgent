# 使用 GitHub Actions 发布到阿里云 ACR

ACR 是容器镜像仓库，不直接部署 JAR。本仓库的发布链路是：GitHub Actions 打包后端 JAR，将 JAR 封装为 Docker 镜像并推送到 ACR；服务器使用 Docker 拉取和运行该镜像。

## 配置 GitHub 仓库

在 GitHub 仓库的 **Settings > Secrets and variables > Actions** 中添加：

| 类型 | 名称 | 示例 |
| --- | --- | --- |
| Variable | `ACR_REGISTRY` | `registry.cn-hangzhou.aliyuncs.com` |
| Variable | `ACR_NAMESPACE` | `your-namespace` |
| Variable | `DOCKER_PLATFORMS` | `linux/amd64` |
| Secret | `ACR_USERNAME` | ACR 访问凭证用户名 |
| Secret | `ACR_PASSWORD` | ACR 访问凭证密码 |

`DOCKER_PLATFORMS` 可省略，默认构建 `linux/amd64`。ARM 服务器使用 `linux/arm64`；同时支持两种架构时填写 `linux/amd64,linux/arm64`。

创建 ACR 命名空间和镜像仓库后，每次有新提交推送到 `main`，都会触发 `.github/workflows/publish-backend-image.yml`。该工作流发布：

```text
<ACR_REGISTRY>/<ACR_NAMESPACE>/data-agent-backend:latest
<ACR_REGISTRY>/<ACR_NAMESPACE>/data-agent-backend:sha-<Git commit SHA>
```

## 准备服务器

将 `docker-file` 目录和生产环境 `application.yml` 放到服务器，例如 `/opt/dataagent/docker-file`。服务器需要安装 Docker 和 Docker Compose Plugin。

后端的 Docker Python 执行器需要访问 Docker 守护进程。若要给后端容器挂载 Docker Socket，先根据生产隔离方案评估其对宿主机的访问权限。

数据库密码、模型 API Key 和 ACR 凭证等运行时密钥应保存在服务器环境变量或 `/opt/dataagent/docker-file/config/application.yml`，不要提交到 Git。

## 通过 SSH 部署

首次在服务器登录私有 ACR：

```bash
docker login registry.cn-hangzhou.aliyuncs.com
```

然后执行部署：

```bash
cd /opt/dataagent/docker-file
chmod +x deploy-backend.sh
DATA_AGENT_BACKEND_IMAGE=registry.cn-hangzhou.aliyuncs.com/your-namespace/data-agent-backend:latest \
  ./deploy-backend.sh
```

脚本会拉取镜像、启动所需 Compose 依赖，并且只重建 `backend` 容器。生产环境建议使用 `sha-<Git commit SHA>` 标签替代 `latest`，这样每次发布都能精确回滚。
