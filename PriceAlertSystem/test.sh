#!/usr/bin/env bash
# Runs the automatic self-tests (works offline).
cd "$(dirname "$0")"
./build.sh && java -cp out com.pricealert.SelfTest
