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
COPY resources-prod ./resources-prod
RUN clojure -T:build uberjar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/reader.jar /app/reader.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/reader.jar"]
