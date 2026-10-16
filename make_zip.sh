#!/usr/bin/env bash
# Bundle the project for Colab. Run from the repo root: ./make_zip.sh
set -euo pipefail

cd "$(dirname "$0")"

rm -f pocketdev.zip
zip -r pocketdev.zip \
    app gradle gradlew gradlew.bat \
    build.gradle.kts settings.gradle.kts \
    gradle.properties \
    -x 'app/build/*' -x '*.iml' -x '.DS_Store' > /dev/null

echo "Created pocketdev.zip ($(du -h pocketdev.zip | cut -f1))"
