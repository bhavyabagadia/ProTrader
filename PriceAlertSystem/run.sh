#!/usr/bin/env bash
# Usage:  ./run.sh            normal menu
#         ./run.sh --demo     30-second automatic demo (no internet needed)
#         ./run.sh --check    test every live data source
#         ./run.sh --plain    use if your terminal shows strange characters
cd "$(dirname "$0")"
[ -d out ] || ./build.sh
java -cp out com.pricealert.App "$@"
