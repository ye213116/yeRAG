FROM maven:3.9.9-eclipse-temurin-17
RUN apt-get update && apt-get install -y --no-install-recommends python3 git ripgrep && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 yerag && useradd --uid 10001 --gid yerag --create-home yerag \
    && mkdir -p /workspace /base /opt/yerag && chown -R yerag:yerag /workspace /base
COPY deploy/coding/helper.py /opt/yerag/helper.py
USER 10001:10001
WORKDIR /workspace
