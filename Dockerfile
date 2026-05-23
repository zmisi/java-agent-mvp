# Build (public ECR mirrors — avoids Podman/Docker Hub stale login on docker.io)
FROM public.ecr.aws/docker/library/maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

# Run (JRE + Node 20 for Postgres MCP stdio server; Ubuntu apt nodejs is v12)
FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @modelcontextprotocol/server-postgres@0.6.2 \
    && ln -sf "$(npm root -g)/@modelcontextprotocol/server-postgres/dist/index.js" /usr/local/bin/mcp-server-postgres.js \
    && node --version \
    && test -f /usr/local/bin/mcp-server-postgres.js \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/java-agent-mvp-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
