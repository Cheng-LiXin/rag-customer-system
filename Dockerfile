# =============================================================================
# 后端镜像：多阶段构建（Maven 编译 → JRE 运行）
# -----------------------------------------------------------------------------
# 构建上下文 = 仓库根目录（compose 里 context: .），因为 pom.xml 与 src/ 都在根下。
# 构建：docker compose --profile full build backend
# =============================================================================

# ---------- 阶段 1：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom 并预拉依赖：这一层能被 Docker 缓存，改 Java 代码时不必重新下载依赖
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 保留 -DskipTests（仅跳过执行，仍会编译测试源码）—— 顺带当作一道编译期自检
RUN mvn -B -q clean package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区必须对齐：业务里有大量 LocalDateTime 比较（会话超时、近 7 天统计），
# 容器默认 UTC 会让「近 7 天消息趋势」等统计偏 8 小时。
ENV TZ=Asia/Shanghai

# 装 curl 供 healthcheck 使用（temurin 基础镜像默认没有），顺手也方便容器内排查
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# 堆内存给个上限，避免容器里无界增长把宿主拖垮（按需用 JAVA_OPTS 覆盖）
ENV JAVA_OPTS="-Xms256m -Xmx768m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=Asia/Shanghai -jar /app/app.jar"]
