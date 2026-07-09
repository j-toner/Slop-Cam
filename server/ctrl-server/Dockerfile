FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o ctrl-server .

FROM alpine:3.20
# ffmpeg drives security-mode recording (libx265 included in alpine build);
# tzdata for local-time timestamps, dejavu for the drawtext overlay font
RUN apk add --no-cache ca-certificates ffmpeg tzdata font-dejavu
WORKDIR /app
COPY --from=builder /app/ctrl-server .
EXPOSE 8080
CMD ["./ctrl-server"]
