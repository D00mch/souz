FROM node:22-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash coreutils findutils python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /souz/home

CMD ["bash", "-lc", "while true; do sleep 3600; done"]
