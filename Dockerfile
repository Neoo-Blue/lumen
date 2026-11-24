# Lumen server. Runs next to Tailscale (see deploy/docker-compose.yml); reachable only on your tailnet.
FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv git ca-certificates bsdutils \
 && python3 -m venv /opt/flights && /opt/flights/bin/pip install --no-cache-dir fast-flights typing_extensions \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server/package.json server/package-lock.json* ./
RUN npm ci --omit=dev 2>/dev/null || npm install --omit=dev
COPY server/ ./
RUN useradd -m -d /data lumen && mkdir -p /data && chown lumen /data
USER lumen
ENV PORT=8080 LUMEN_DATA=/data NODE_ENV=production LUMEN_FLIGHTS_PY=/opt/flights/bin/python
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "server.mjs"]
