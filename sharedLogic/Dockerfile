FROM node:22-bookworm

LABEL ru.souz.runtime-sandbox.fixture="paper-summarize-academic"

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash coreutils findutils python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY docker/entrypoint.sh /usr/local/bin/souz-runtime-entrypoint
COPY docker/skills /opt/souz/skills

RUN chmod 0755 /usr/local/bin/souz-runtime-entrypoint \
    && chmod -R a+rX /opt/souz/skills

WORKDIR /souz/home

ENTRYPOINT ["/usr/local/bin/souz-runtime-entrypoint"]
CMD ["bash", "-lc", "while true; do sleep 3600; done"]
