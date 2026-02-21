#!/bin/bash

export BUILD_EDITION=ru

./gradlew :composeApp:notarizeReleaseDmg \
  -Pedition="$BUILD_EDITION" \
  -Pmac.signing.enabled=true \
  -Pmac.signing.identity="$APPLE_SIGNING_ID" \                   # Developer ID Application sertificate name
  -Pmac.notarization.enabled=true \
  -Pmac.notarization.appleId="$APPLE_ID" \                       # email
  -Pmac.notarization.password="$APPLE_APP_SPECIFIC_PASSWORD" \   # app-specific password, https://account.apple.com
  -Pmac.notarization.teamId=A6VYB9APPM