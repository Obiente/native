FROM node:22-alpine AS build

WORKDIR /source

RUN apk add --no-cache git

COPY website/package.json website/package-lock.json ./website/
RUN npm ci --prefix website

COPY . .
RUN npm run --prefix website build

FROM nginx:stable-alpine

RUN apk add --no-cache curl

COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /source/website/dist /usr/share/nginx/html
COPY --from=build /source/website/dist-indexnow /opt/indexnow
COPY --chmod=755 website/docker/indexnow-notify.sh /docker-entrypoint.d/40-indexnow-notify.sh

EXPOSE 80
