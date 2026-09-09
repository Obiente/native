FROM node:22-alpine AS build

WORKDIR /source

RUN apk add --no-cache git

COPY website/package.json website/package-lock.json ./website/
RUN npm ci --prefix website

COPY . .
RUN npm run --prefix website build

FROM nginx:stable-alpine

RUN apk add --no-cache curl

ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER=^NGINX_LOCAL_RESOLVERS$

COPY website/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /source/website/dist /usr/share/nginx/html
COPY --from=build /source/website/dist-indexnow /opt/indexnow
COPY --chmod=755 website/docker/indexnow-notify.sh /docker-entrypoint.d/40-indexnow-notify.sh

EXPOSE 80
