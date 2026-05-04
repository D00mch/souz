#!/usr/bin/env bash
set -euo pipefail

mkdir -p /souz/state/skills

if [ -d /opt/souz/skills ]; then
  cp -Rn /opt/souz/skills/. /souz/state/skills/
fi

exec "$@"
