# The backup server: one static binary in an empty image. No shell, no interpreter, nothing else.
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
# The one library and what it brings, checked against the hashes in go.sum.
RUN go mod download && go mod verify
COPY main.go ./
COPY backup ./backup
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /lhoop-backup .

FROM scratch
COPY --from=build /lhoop-backup /lhoop-backup
ENV LHOOP_DATA=/data
USER 10001:10001
EXPOSE 8787
ENTRYPOINT ["/lhoop-backup"]
CMD ["serve"]
