# MySQL 初始化、迁移与隔离验收

空库从七表基线开始，再追加知识索引和知识问答账本迁移。对已有业务库应先比较实际 `SHOW CREATE TABLE`，再在备份上演练。

## 空库初始化

使用 MySQL **8.0.19 及以上**，InnoDB、`utf8mb4`、`utf8mb4_0900_ai_ci`。知识索引清理的幂等更新使用该版本引入的插入行别名语法。本地验证使用 MySQL 9.4.0。数据库名由操作者明确选择，脚本不包含 `USE`、`DROP` 或账号种子。[MySQL 8.0.19 发布说明](https://dev.mysql.com/doc/relnotes/mysql/8.0/en/news-8-0-19.html)

先创建空库（此例为应用默认库名；同名库已存在时应先确认它确实是目标库）：

```sql
CREATE DATABASE mental_health_assistant
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

从后端目录在 PowerShell 导入，密码使用客户端提示输入：

```powershell
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath db/baseline/20261106_schema.sql -Raw |
  mysql --default-character-set=utf8mb4 --host=127.0.0.1 --user=YOUR_USER --password --database=mental_health_assistant
if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败' }
Get-Content -LiteralPath db/migrations/20261107_add_knowledge_index.sql -Raw |
  mysql --default-character-set=utf8mb4 --host=127.0.0.1 --user=YOUR_USER --password --database=mental_health_assistant
if ($LASTEXITCODE -ne 0) { throw '知识索引迁移失败' }
Get-Content -LiteralPath db/migrations/20261107_allow_knowledge_qa_requests.sql -Raw |
  mysql --default-character-set=utf8mb4 --host=127.0.0.1 --user=YOUR_USER --password --database=mental_health_assistant
if ($LASTEXITCODE -ne 0) { throw '知识问答账本迁移失败' }
```

基线包含 `user`、`consultation_session`、`consultation_message`、`knowledge_category`、`knowledge_article`、`emotion_diary`、`ai_request_record` 七表。重复导入使用 `CREATE TABLE IF NOT EXISTS`，保留已有数据，不创建演示账号、不覆盖密码、文章或历史记录。已有表不会被基线自动升级；表结构漂移需要比较 `SHOW CREATE TABLE` 后单独迁移。分类表仅含当前实体消费的字段；历史库可保留 `category_code/sort_order` 等额外列。

时间列使用 `DATETIME(6)`。请求账本由 Java 按 `Asia/Shanghai` 写入，JDBC URL 应显式指定 `serverTimezone=Asia/Shanghai`；数据库会话也建议 `+08:00`。不要把 `DATETIME` 当成包含时区的瞬时值。

## 现有库追加迁移

暂停写入、备份并核对目标数据库的 `SHOW CREATE TABLE` 后，按顺序执行未执行的迁移。MySQL DDL 会隐式提交，不能依赖外层事务将整份脚本全部回滚。先在备份或测试副本上演练，逐条确认结果；遇到失败停止，不启动新版本服务。

1. 已有历史库缺软删除列时，执行 `migrations/20261102_add_missing_deleted_columns.sql`。此历史脚本含固定 `USE mental_health_assistant`，只能用于该库名；更早的单表脚本已被它覆盖，无需重复执行。
2. 执行 `migrations/20261106_add_ai_request_record.sql`。此新增脚本使用客户端明确选择的库，创建请求账本、为消息追加可空 `request_id` 和 `(request_id,sender_type)` 唯一键，并将较短的 `VARCHAR ai_model` 扩到 100 字符。扩容保留字符集、排序规则、可空性、默认值和注释；不改其他列类型，非 `VARCHAR` 需人工对照。新实体和新库同样使用 100 字符上限。
3. 执行 `migrations/20261107_add_knowledge_index.sql`。脚本追加 `knowledge_article` 的索引列，创建 `knowledge_index_config`、`knowledge_index_job`、`knowledge_chunk`。已有文章保留原内容和发布状态，初始正文修订号为 `1`，目标和活动索引为空，`index_ready=false`。需要检索的历史文章由管理员显式触发重建。
4. 执行 `migrations/20261107_allow_knowledge_qa_requests.sql`。脚本将账本的 `session_id` 放宽为可空，保留已有咨询行和列的 `BIGINT` 类型、默认值、注释及可见性。知识问答用独立的 `knowledge-qa` 场景，三个会话及消息关联字段均为空。[MySQL 列定义修改说明](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)

第三批迁移重复执行不会重复添加列/索引或修改历史消息。旧消息关联为 `NULL`，MySQL 唯一索引允许多条 `NULL`，无需伪造历史请求。唯一索引与新表若已存在，不自动纠正同名但定义不一致的对象；应检查 `SHOW CREATE TABLE`。没有自动迁移框架，本批仍为显式手动迁移。

空库基线已含第三批字段，随后运行知识索引和知识问答迁移。迁移重复执行时保留已写入的索引状态、任务、分块和请求元数据。同名对象的定义仍须使用 `SHOW CREATE TABLE` 对照。请求与索引关联由事务和唯一键维护。咨询问答正文保留在原消息表。

升级前需要应用知识索引迁移。即使关闭索引工作器，文章编辑和状态变更仍会维护修订号及索引状态。

索引任务的到期判断和时间写入使用 MySQL `UTC_TIMESTAMP(6)`，与会话时区和 JVM 时区分离。配置版本独占集合，`BUILD` 和 `CLEANUP` 各自按构建代次去重。过期租约换新令牌后继续同一代次。清理只匹配旧文章修订号、配置版本和代次。

## 请求恢复与保留边界

部署范围为**单实例**。服务初始化时同步恢复 `RUNNING`。咨询找到已提交、会话匹配的 AI 消息才恢复成功，否则转为 `UNKNOWN`。知识问答直接转为 `UNKNOWN`，重试返回 409。启动恢复只处理账本和咨询已提交消息。多个实例同时使用该恢复规则会影响其他实例的活跃任务。

同一用户、场景、客户端 ID 在终止后 24 小时内复用原请求；不同会话、正文或影响输出的模型参数会冲突。过期清理只删除终态账本元数据，不删除咨询消息，也不清理 `RUNNING`。删除会话后不允许重放其结果。唯一键不能证明外部提供方跨网络故障恰好执行一次。

知识问答账本保存请求指纹、模型标识和安全状态码。答案和引用只在应用内的临时缓存中保留，缓存失效后的同 ID 查询返回 409。

## 可复现的真实 MySQL 验收

`verify-mysql.ps1` 在本项目 `target` 下生成全新数据目录，只绑定 `127.0.0.1` 随机空闲端口，使用虚构数据。它不读取应用配置，不连接现有 MySQL 服务，不创建 Windows 服务，结束时关闭自己启动的实例；日志和临时数据保留在对应 `target/batch3-mysql-*` 目录。

```powershell
# 后端目录；默认路径可通过参数替换，不修改系统 Java/Node 状态。
powershell -NoProfile -ExecutionPolicy Bypass -File db/verify-mysql.ps1 `
  -MySqlBase 'E:\MySQL\MySQL Server 9.4' `
  -JavaHome 'E:\JDK17' `
  -Maven 'E:\apache-maven-3.9.15\bin\mvn.cmd'
```

需要已有本地 Maven 依赖缓存（脚本使用 `-o`），以及当前终端允许本机 TCP/启动子进程。MySQL 测试目录通过 `--initialize-insecure` 创建临时无密码 root，只用于该回环实例，不能用于实际部署。初始化目录不会被脚本自动删除。

真实验证调用生产 `AiRequestService`，覆盖并发唯一键、用户消息与请求同事务、AI 消息与终态同事务、触发器故障注入回滚、历史消息保留、终态 TTL、软删除和越权保护。额外的两次独立 JVM 在私有 MySQL 优雅重启前后验证 checkpoint：已提交消息恢复成功，未确认请求保持 `UNKNOWN`。模型使用 Stub，不调用外部服务。数据库集成测试命名为 `*IT`，普通 `mvn test` 不扫描它们；验收脚本显式执行并检查零失败、零跳过。

使用同一隔离脚本验证知识索引。它先插入虚构历史文章，再应用本批迁移并执行 `KnowledgeIndexMysqlIT`。证据保存在 `target/batch5-index-mysql-*`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File db/verify-mysql.ps1 -Suite KnowledgeIndex
```

若已另行启动隔离的本机 Qdrant，加入 `-QdrantGrpcPort <端口>` 可追加 `KnowledgeIndexQdrantIT`。该端口应属于本次验收的独立实例。MySQL 脚本负责关闭自身启动的数据库实例，Qdrant 生命周期由其启动者管理。

知识问答账本验收使用以下命令。证据目录为 `target/batch6-qa-mysql-*`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File db/verify-mysql.ps1 -Suite KnowledgeQa
```

固定样本联合评测由后端 `tools/rag-smoke/verify-qdrant.ps1 -Mode QaSuite` 启动两项隔离依赖。数据库验收汇总只记录评测测试计数，样本结果由评测脚本写入专用目录。

## Compose 迁移、备份与恢复

在 Linux 或 WSL 的 Bash 中操作，并在仓库根目录准备该环境的 `.env`。Compose 按 `010`、`020`、`030` 依次将七表基线、知识索引和问答账本迁移挂载到 MySQL 初始化目录。镜像只在空数据目录执行初始化脚本。已有卷升级先停写并准备备份，再查看迁移清单，最后显式应用。 [MySQL 容器初始化说明](https://dev.mysql.com/doc/refman/8.4/en/docker-mysql-more-topics.html)

```bash
docker compose -p healthagent stop frontend backend
bash deployment/scripts/migrate.sh healthagent
bash deployment/scripts/migrate.sh healthagent --apply
```

迁移脚本使用 MySQL 容器的应用账号和本地 socket。旧软删除脚本由客户端选定数据库，执行时移除原脚本固定的 `USE` 行。该脚本要求已有完整的基础业务表。对于与当前结构不一致的历史库，先用已验证的原环境备份方式保留原数据，再演练升级。

备份使用 `database.sql`、`uploads.tar.gz`、表结构摘要和 SHA-256 校验清单。开始前停止应用写入，并暂停其他连接的直接写入及 DDL，直到脚本结束。SQL 采用同一事务快照，上传卷在同一停写窗口归档。将备份目录放在仓库外的受限位置。此格式适用于当前 InnoDB 表结构，恢复目标需先初始化相同的表、列及索引。 [MySQL 逻辑备份说明](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)

```bash
docker compose -p healthagent stop frontend backend
bash deployment/scripts/backup.sh healthagent /private/healthagent-backup-20261108
docker compose -p healthagent start backend frontend
```

恢复使用不同的 Compose 项目名。先只启动目标 MySQL，保持目标后端、前端和 Qdrant 停止。工具核对校验和、归档路径、卷归属和目标空库，再恢复上传目录。SQL 导入与失效旧索引指针在同一事务提交。失败后保持目标停止，保留现场并在另一个新项目重试。

```bash
docker compose -p healthagent-recovery up -d --wait mysql
docker compose -p healthagent-recovery build backend
bash deployment/scripts/restore.sh healthagent-recovery /private/healthagent-backup-20261108
```

恢复源包含 MySQL 数据和上传文件。目标使用空 Qdrant 卷。恢复时清除文章的活动索引指针，并终止旧的待办索引任务。启用 RAG 时先按目标配置预建集合，启动后端后由管理员重建已发布文章索引。与源环境同时运行时，为目标设置独立的 `APP_PORT`。

```bash
docker compose -p healthagent-recovery --profile rag-init run --rm qdrant-init
APP_PORT=8081 docker compose -p healthagent-recovery up -d --wait
```

工具需要 Bash、Docker Compose、`sha256sum` 和 Python 3。SQL 和上传归档还可用 `python deployment/scripts/restore-drill.py --mysql-base 'E:/MySQL/MySQL Server 9.4'` 在本机原生 MySQL 演练。该演练创建隔离实例和两个虚构库，检查失败回滚、上传字节、索引失效及实例关闭，结果保存在后端 `target/batch7-native-restore-*`。
