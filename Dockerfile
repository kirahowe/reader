FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates rlwrap \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/install.sh https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh \
 && chmod +x /tmp/install.sh \
 && /tmp/install.sh
COPY deps.edn build.clj ./
RUN clojure -P -T:build
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uberjar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/reader.jar /app/reader.jar
COPY env/prod/resources /app/conf
EXPOSE 8080
# ENTRYPOINT is the JVM prefix; CMD is the default command (the app server).
# Split so Fly's release_command (which Fly runs as ENTRYPOINT + release_command,
# replacing CMD) becomes `java … reader.migrate prod.edn` rather than extra args
# to reader.main. See fly.toml [deploy] release_command.
ENTRYPOINT ["java", "-cp", "/app/conf:/app/reader.jar"]
CMD ["reader.main", "prod.edn"]
