# Java 17 + PowerShell Core so we can run Windows-style commands on Linux,
# on both amd64 (Intel) and arm64 (Apple Silicon) hosts.
FROM eclipse-temurin:17-jdk-bookworm

ARG PWSH_VERSION=7.4.6

# Runtime dependencies PowerShell needs on Debian.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl libicu72 libssl3 less locales \
    && rm -rf /var/lib/apt/lists/*

# Install PowerShell from the official tarball matching the build architecture.
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) pwsh_arch="x64" ;; \
        arm64) pwsh_arch="arm64" ;; \
        *) echo "unsupported arch: $arch" && exit 1 ;; \
    esac; \
    url="https://github.com/PowerShell/PowerShell/releases/download/v${PWSH_VERSION}/powershell-${PWSH_VERSION}-linux-${pwsh_arch}.tar.gz"; \
    mkdir -p /opt/microsoft/powershell/7; \
    curl -L "$url" -o /tmp/powershell.tar.gz; \
    tar zxf /tmp/powershell.tar.gz -C /opt/microsoft/powershell/7; \
    chmod +x /opt/microsoft/powershell/7/pwsh; \
    ln -sf /opt/microsoft/powershell/7/pwsh /usr/bin/pwsh; \
    rm -f /tmp/powershell.tar.gz; \
    pwsh --version

WORKDIR /app

COPY . .

# Pre-compile the Java application
RUN mkdir -p target/classes target/test-classes \
    && javac -d target/classes $(find src/main/java -name "*.java") \
    && javac -cp target/classes -d target/test-classes $(find src/test/java -name "*.java")

# Default to an interactive shell so you can exec in and run the agent.
CMD ["bash"]
