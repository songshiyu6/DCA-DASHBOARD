FROM node:22-alpine AS build

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY

WORKDIR /app

COPY apps/web/package.json apps/web/package-lock.json ./
RUN HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" npm ci

COPY apps/web/index.html apps/web/tsconfig.json apps/web/tsconfig.app.json apps/web/tsconfig.node.json ./
COPY apps/web/vite.config.ts apps/web/vitest.config.ts ./
COPY apps/web/src ./src

ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

FROM nginx:1.27-alpine

COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
