FROM mcr.microsoft.com/devcontainers/java:25-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git-lfs rlwrap \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/install-clojure.sh https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh \
    && chmod +x /tmp/install-clojure.sh \
    && /tmp/install-clojure.sh \
    && rm /tmp/install-clojure.sh

RUN curl -fsSL -o /tmp/install-bb.sh https://raw.githubusercontent.com/babashka/babashka/master/install \
    && chmod +x /tmp/install-bb.sh \
    && /tmp/install-bb.sh --dir /usr/local/bin \
    && rm /tmp/install-bb.sh
