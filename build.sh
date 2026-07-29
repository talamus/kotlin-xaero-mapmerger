#!/usr/bin/env bash
# Builds XaeroMapmerger.jar (self-contained, includes the Kotlin runtime).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v kotlinc >/dev/null 2>&1; then
    set +u  # sdkman-init.sh references unset variables
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
fi

kotlinc src/main/kotlin -include-runtime -d XaeroMapmerger.jar
echo "Built XaeroMapmerger.jar"
