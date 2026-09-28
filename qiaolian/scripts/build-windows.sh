#!/bin/sh
set -e
cd "$(dirname "$0")/.."
mkdir -p dist
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/qiaolian.exe ./cmd/qiaolian
echo "已生成 dist/qiaolian.exe"
