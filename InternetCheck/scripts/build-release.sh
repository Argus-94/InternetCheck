#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Укажите путь Android SDK в ANDROID_HOME}"
: "${IC_KEYSTORE:?Укажите абсолютный путь к release keystore}"
: "${IC_STORE_PASSWORD:?Укажите пароль keystore}"
: "${IC_KEY_ALIAS:?Укажите alias release-ключа}"
: "${IC_KEY_PASSWORD:?Укажите пароль release-ключа}"
npm ci --no-fund
npm run typecheck
npm run lint
npx expo prebuild --platform android --no-install
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
chmod +x android/gradlew
./android/gradlew -p android :net-check:testDebugUnitTest :net-check:lintDebug :app:lintRelease :app:assembleRelease --console=plain --max-workers=2
printf '\nAPK: %s/android/app/build/outputs/apk/release/app-release.apk\n' "$PWD"
