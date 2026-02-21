#!/bin/bash
set -euo pipefail

: "${APPLE_SIGNING_ID:?Missing APPLE_SIGNING_ID}"
: "${APPLE_ID:?Missing APPLE_ID}"
: "${APPLE_APP_SPECIFIC_PASSWORD:?Missing APPLE_APP_SPECIFIC_PASSWORD}"

export BUILD_EDITION=ru

./gradlew :composeApp:notarizeReleaseDmg \
  -Pedition="$BUILD_EDITION" \
  -PmacOsAppStoreRelease=false \
  -Pmac.signing.enabled=true \
  -Pmac.signing.identity="$APPLE_SIGNING_ID" \
  -Pmac.notarization.enabled=true \
  -Pmac.notarization.appleId="$APPLE_ID" \
  -Pmac.notarization.password="$APPLE_APP_SPECIFIC_PASSWORD" \
  -Pmac.notarization.teamId=A6VYB9APPM
