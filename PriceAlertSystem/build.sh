#!/usr/bin/env bash
# Compiles the project into ./out  (needs JDK 17 or newer: java -version)
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
echo "Build OK -> ./out"
