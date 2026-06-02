# Build context must be the parent directory (see docker-compose.yml):
#   git/admission-score-mcp + git/java-agent-mvp as siblings

FROM public.ecr.aws/docker/library/maven:3.9.9-eclipse-temurin-17 AS build-java
WORKDIR /app/java-agent-mvp
COPY java-agent-mvp/pom.xml java-agent-mvp/mvnw ./
COPY java-agent-mvp/.mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY java-agent-mvp/src src
COPY java-agent-mvp/docs/design docs/design
COPY java-agent-mvp/db/releases db/releases
RUN ./mvnw -B -DskipTests package

FROM public.ecr.aws/docker/library/node:20-bookworm-slim AS build-mcp
WORKDIR /app/admission-score-mcp
COPY admission-score-mcp/package.json admission-score-mcp/package-lock.json ./
RUN npm ci
COPY admission-score-mcp/tsconfig.json ./
COPY admission-score-mcp/src ./src
RUN npm run build

FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && node --version \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build-java /app/java-agent-mvp/target/java-agent-mvp-*.jar app.jar
COPY --from=build-java /app/java-agent-mvp/docs/design docs/design
COPY --from=build-java /app/java-agent-mvp/db/releases db/releases
COPY --from=build-mcp /app/admission-score-mcp/dist /app/admission-score-mcp/dist
COPY --from=build-mcp /app/admission-score-mcp/node_modules /app/admission-score-mcp/node_modules
COPY --from=build-mcp /app/admission-score-mcp/package.json /app/admission-score-mcp/package.json
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
