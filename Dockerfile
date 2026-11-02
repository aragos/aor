FROM node:24-bookworm-slim AS web
WORKDIR /build
COPY package.json package-lock.json ./
COPY web/package.json ./web/package.json
RUN npm ci --ignore-scripts
COPY web ./web
RUN npm run build

FROM eclipse-temurin:23-jdk-noble AS server
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server
RUN ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process :server:installDist

FROM eclipse-temurin:23-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 aor && useradd --uid 10001 --gid 10001 --no-create-home aor
WORKDIR /app
COPY --from=server /build/server/build/install/server /app/server
COPY --from=web /build/web/dist /app/web
COPY deployment/entrypoint.sh /app/entrypoint.sh
ENV AOR_HTTP_HOST=0.0.0.0 AOR_AUTH_MODE=firebase AOR_WEB_DIR=/app/web \
    AOR_STORAGE=postgres PORT=10000 \
    JAVA_OPTS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=2 -Xss512k"
EXPOSE 10000
# All durable state lives in PostgreSQL; the application needs no writable host volume.
USER 10001:10001
ENTRYPOINT ["/bin/sh", "/app/entrypoint.sh"]
