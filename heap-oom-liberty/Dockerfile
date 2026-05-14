# ===== Build Stage =====
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /build

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy source code and build
COPY src ./src
RUN mvn -B clean package

# ===== Runtime Stage with IBM Semeru Runtime =====
FROM ibm-semeru-runtimes:open-21-jre-jammy

# Set environment variables for heap dump on OOM
ENV JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps"

# Create necessary directories
RUN mkdir -p /opt/ol/wlp/usr/servers/defaultServer /dumps && \
    chown -R 1001:0 /opt/ol /dumps && \
    chmod -R g+rw /opt/ol /dumps

# Install Open Liberty
ARG LIBERTY_VERSION=24.0.0.3
ARG LIBERTY_SHA=d9c8f5c5e3e3e3e3e3e3e3e3e3e3e3e3

# Download and install Open Liberty
RUN apt-get update && apt-get install -y wget unzip && \
    wget -q https://public.dhe.ibm.com/ibmdl/export/pub/software/openliberty/runtime/release/${LIBERTY_VERSION}/openliberty-${LIBERTY_VERSION}.zip && \
    unzip -q openliberty-${LIBERTY_VERSION}.zip -d /opt && \
    mv /opt/wlp /opt/ol/wlp && \
    rm openliberty-${LIBERTY_VERSION}.zip && \
    apt-get remove -y wget unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy server configuration
COPY --chown=1001:0 src/main/liberty/config/server.xml /opt/ol/wlp/usr/servers/defaultServer/

# Copy the built WAR file
COPY --chown=1001:0 --from=build /build/target/heap-oom-liberty.war /opt/ol/wlp/usr/servers/defaultServer/apps/

# Set working directory
WORKDIR /opt/ol/wlp

# Expose port
EXPOSE 8080

# Switch to non-root user
USER 1001

# Start Open Liberty
ENTRYPOINT ["/bin/sh", "-c", "exec /opt/ol/wlp/bin/server run defaultServer"]