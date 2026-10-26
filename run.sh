#!/usr/bin/env bash
# Compile and start the autocomplete demo.   ./run.sh          (interactive)
#                                            ./run.sh test     (test suite)
#                                            ./run.sh bench    (benchmark, about 2 minutes)
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out src/*.java
java -cp out Main "$@"
