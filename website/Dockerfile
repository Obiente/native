FROM node:22-alpine AS build

WORKDIR /source

COPY website/package.json website/package-lock.json ./website/
RUN npm ci --prefix website

COPY . .
RUN npm run --prefix website build

FROM nginx:stable-alpine

COPY website/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /source/website/dist /usr/share/nginx/html

EXPOSE 80
