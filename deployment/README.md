# Compose 部署与恢复手册

## 启动

使用可运行 Linux 容器的 Docker Engine 28.0 或更新版本和 Compose v2。在仓库根目录按样例新建 `.env`。保留已有本地文件，填写相互独立的 MySQL 应用密码、MySQL root 密码、至少 32 字节的 JWT 密钥，以及实际 Chat 配置。可用 `openssl rand -hex 32` 为每个本地密钥生成独立值。 [配置样例](../.env.example) · [Docker 端口发布说明](https://docs.docker.com/engine/network/port-publishing/)

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
# 编辑 .env 后执行
docker compose -p healthagent config --quiet
docker compose -p healthagent up -d --build --wait
docker compose -p healthagent ps
curl.exe --fail http://127.0.0.1:8080/healthz
curl.exe --fail http://127.0.0.1:8080/api/test
```

默认浏览器入口为 `http://127.0.0.1:8080`，可在 `.env` 更改 `APP_PORT`。容器使用服务名 `backend:8082`、`mysql:3306`、`qdrant:6334`。仅 Nginx 发布宿主机端口。数据网为 Compose 内部网，模型调用由后端经应用网络出站。默认部署用于本机和演示。如果实际站点需要对外服务，需为选定域名准备 TLS 入口。 [Compose 配置](../compose.yaml)

初次初始化按顺序应用七表基线、知识索引和知识问答迁移。启动前端后可注册普通用户。管理员账号由受控运维操作授权。将已核实的账号 ID 更新为 `user_type=2`，再重新登录取得管理员角色。现有库按数据库说明检查和运行缺失迁移。镜像只从环境读取凭据，使用现有 MySQL 卷时，修改 `.env` 中的密码需配套执行数据库账号轮换。 [数据库说明](../mental-health-assistant-backend/db/README.md)

启用知识问答时，先用所需维度创建 Cosine 集合，再设置 `.env` 的 `RAG_ENABLED=true`、Embedding 凭据和配置，重建后端容器。已存在同名集合时，初始化程序核对维度与距离类型。模型、维度或分块配置变更时使用新版本和新集合，再由管理员重建文章索引。

```powershell
docker compose -p healthagent run --rm qdrant-init
# 编辑 .env 后执行
docker compose -p healthagent up -d --wait backend frontend
```

## 路由和健康

| 入口 | 行为 |
| --- | --- |
| `/api/psychological-chat/stream` 和 `/api/psychological-chat/stream/attach/{sessionId}` | 代理保留原路径，禁用缓冲、缓存和压缩，150 秒空闲读取超时。应用每个观察连接约 15 秒发送注释心跳 |
| 其余 `/api/` | 代理保留路径及 `token` 头，75 秒空闲读取超时。问答和分析由应用执行各自总截止 |
| `/upload/` | 代理保留路径。生产构建默认用当前页面源访问图片 |
| Vue 子路由 | 找不到静态文件时回退到 `index.html`，刷新由 Vue 路由解析 |
| `/healthz` | Nginx 存活探针 |
| 后端 `/internal/health/live` 与 `/internal/health/ready` | 容器内探针，Nginx 拒绝该路径。就绪检查覆盖 MySQL 连接和核心列，版本迁移另行核对。MySQL 不可用时 readiness 返回 503，进程仍可存活。Qdrant 与模型故障由受影响接口报告 |

日常停止使用 `docker compose -p healthagent down`，保留 MySQL、Qdrant 和上传三个命名卷。正常容器重建从原卷读取数据。进程中断时，数据库里的未完成请求在重启后按账本恢复为已提交结果或待确认状态。

## 备份与恢复演练

脚本从 Bash 运行，主机需要 Docker Compose、Python 3 和 `sha256sum`。下列名称用于说明，请替换成已核对的源项目和全新目标项目。准备两份配置，目标使用独立凭据和端口。使用 `COMPOSE_ENV_FILES` 为每组命令选择环境文件。备份目标目录应为此前不存在的受控位置，产物包含私人数据库和上传文件，应按其数据级别存取。

```bash
# 仓库根目录，源项目停写窗口
export COMPOSE_ENV_FILES=/absolute/path/to/source.env
docker compose -p healthagent stop frontend backend
bash deployment/scripts/backup.sh healthagent /absolute/path/to/new-backup
docker compose -p healthagent up -d --wait frontend

# 新项目先构建镜像，只启动和初始化 MySQL
export COMPOSE_ENV_FILES=/absolute/path/to/restore.env
docker compose -p healthagent-restore build backend frontend
docker compose -p healthagent-restore up -d --wait mysql
bash deployment/scripts/restore.sh healthagent-restore /absolute/path/to/new-backup
docker compose -p healthagent-restore run --rm qdrant-init
docker compose -p healthagent-restore up -d --wait
```

备份由停写窗口内的 MySQL 逻辑数据、上传归档、数据库结构记录和 SHA-256 清单组成。Qdrant 为派生数据，常规重启沿用其卷，跨环境恢复使用空卷重建。恢复脚本要求新项目、空数据库、空上传卷和空 Qdrant 卷。导入时同事务清空活动索引指针并终止旧索引任务。应用恢复后，先核对登录、历史消息、文章和上传内容，再按当前配置重建已发布文章的索引。重建前，问答不会引用旧代次。任何恢复步骤失败时先保留目标项目用于排查，下一次演练使用新的目标。 [恢复脚本](scripts/restore.sh)

## 虚构环境复跑

`start-fixture.sh` 创建新项目、随机本机端口和独立测试凭据，再启动私有模型替身。它需要 OpenSSL。脚本打印本次运行目录，不输出密钥。`exercise.py` 只接受该脚本创建的隔离项目，从空数据库开始，验证包括故意中断后端和停止数据服务。运行数据位于后端忽略的 `target/batch7`。

```bash
bash deployment/verification/start-fixture.sh
python3 deployment/verification/exercise.py /absolute/path/to/run.XXXXXXXX
# 查看运行目录的 exercise-results.json 和 fixture-state.json
```

完成后可用同一项目名、同一 `.env` 和两个 Compose 文件执行 `down`，命名卷仍可用于复查。
