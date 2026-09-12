# MySQL 容器初始化目录

这个目录被 `docker-compose.yml` 挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d`，
容器**首次初始化**（数据卷为空）时会按文件名字母序依次执行里面的 `.sql`。

## 两种填法，按场景选一种

### A. 迁移现有数据（本机演示推荐）

让容器里的数据与你本机现在跑的完全一致，不必重新导入知识库、不用重新调 embedding 向量化。

```powershell
pwsh tools\docker\export-mysql.ps1
# 产出 01_rag_customer.sql
```

### B. 全新初始化（推到云服务器时用）

在服务器上没有"现有数据"可迁，直接用仓库里的建表脚本起步：

```powershell
copy src\main\resources\schema.sql tools\docker\initdb\01_schema.sql
```

`schema.sql` 自带 `DROP TABLE IF EXISTS` + `CREATE` + 种子数据（分类、角色、权限、演示账号），
`DataInitializer` 会在应用启动时补齐 demo 账号的口令与角色绑定。
**注意它是 DROP+CREATE**，只在空库上跑，别拿它去覆盖已有数据的库。

## ⚠ 最常见的坑

**`docker-entrypoint-initdb.d` 只在数据卷为空时执行一次。**

改了这里的 SQL 再 `up -d` 是不会生效的 —— 你会以为改动没生效，其实是根本没跑。要重新初始化：

```powershell
docker compose --profile full down
docker volume rm rag-customer-system_mysql-data    # ★ 只删 MySQL 卷
docker compose --profile full up -d --build
```

**千万不要用 `docker compose down -v`** —— 它会把 `pg-data` 一起删掉，
那里存着向量库的全部向量；清掉后知识片段会全部退回 `vector_status=2`，
得重新向量化（花钱、花时间）。

想确认初始化到底跑了没有：

```powershell
docker compose --profile full logs mysql | Select-String "entrypoint"
```

或起来后直接查表数：

```powershell
docker exec -it rag-mysql mysql -uroot -p123456 -e "USE rag_customer; SELECT COUNT(*) FROM knowledge_chunk WHERE deleted=0;"
```
