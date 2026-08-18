#!/bin/sh
# Builds jcvm. Requires a JDK (javac); Java 8 or newer.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
find src -name '*.java' > /tmp/jcvm-sources.txt
javac -d out @/tmp/jcvm-sources.txt
echo "built -> out/"
echo "run with: ./run.sh"
