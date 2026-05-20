# syntax=docker/dockerfile:1.7
#
# Issue #113 — production image for the Angular frontend.
#
# Stage 1 builds the Angular bundle with Node 20 (matches the .nvmrc family
# used in CI), then stage 2 serves the static output from Nginx Alpine.
#
# Final image stays small (~25–30 MB) because we discard node_modules + the
# entire build toolchain along with stage 1.

# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

# Cache npm install across rebuilds: copy lockfile first, install, then code.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# `application` builder (Angular 18) emits to dist/<project>/browser by default.
RUN npx ng build --configuration=production

# ── Stage 2: serve ───────────────────────────────────────────────────────────
FROM nginx:1.27-alpine AS runtime

# Drop the default site so our own server block is the only one in play.
RUN rm /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/conf.d/altrix.conf
COPY --from=build /app/dist/altrix-frontend/browser /usr/share/nginx/html

# Nginx Alpine listens on 80 by default — keep the contract simple so the
# orchestrating compose / k8s manifest can map any external port to 80.
EXPOSE 80

# Healthcheck: GET / must return < 400 — covers both nginx up AND index.html
# present in the document root.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://localhost/ || exit 1

# nginx:alpine ships with a non-root nginx user; keep the default entrypoint
# (which runs `nginx -g 'daemon off;'`) so logs go to stdout/stderr.
