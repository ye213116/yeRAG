FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends squid ca-certificates && rm -rf /var/lib/apt/lists/*
USER proxy
ENTRYPOINT ["squid", "-N", "-f", "/etc/squid/yerag.conf"]
