#!/bin/sh
# Starts the simulator shell.
cd "$(dirname "$0")"
exec java -cp out jcvm.Main -t res/api-tokens.txt "$@"
