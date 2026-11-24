# Lumen server. Runs next to Tailscale (see deploy/docker-compose.yml); reachable only on your tailnet.
FROM node:22-alpine
RUN apk add --no-cache python3 util-linux-misc git
WORKDIR /app
COPY server/package.json server/package-lock.json* ./
RUN npm ci --omit=dev 2>/dev/null || npm install --omit=dev
COPY server/ ./
RUN adduser -D -h /data lumen && mkdir -p /data && chown lumen /data
USER lumen
ENV PORT=8080 LUMEN_DATA=/data NODE_ENV=production
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "server.mjs"]
