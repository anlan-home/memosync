# memosync 服务端镜像（多阶段：构建 + 极简运行层）
FROM golang:1.24-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o /out/memosync ./cmd/memosync

FROM alpine:3.20
RUN adduser -D -u 1000 memo && apk add --no-cache tzdata ca-certificates
COPY --from=build /out/memosync /usr/local/bin/memosync
USER memo
WORKDIR /data
EXPOSE 5231
VOLUME ["/data"]
ENTRYPOINT ["memosync", "-config", ""]
# 数据目录通过环境变量注入：MEMOSYNC_DATA_DIR=/data
