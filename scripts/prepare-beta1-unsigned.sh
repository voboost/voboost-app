#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "usage: scripts/prepare-beta1-unsigned.sh <output-apk>" >&2
  exit 2
fi

output="$1"
repository="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$repository"
[[ ! -e "$output" && ! -L "$output" ]] || {
  echo "refusing stale output: $output" >&2
  exit 2
}
[[ -d "$(dirname "$output")" ]] || {
  echo "output parent does not exist: $(dirname "$output")" >&2
  exit 2
}

for name in KEYSTORE_PASSWORD VOBOOST_RELEASE_KEYSTORE_FILE VOBOOST_ANDROID_KEYSTORE_BASE64 \
  ORG_GRADLE_PROJECT_debuggable \
  ORG_GRADLE_PROJECT_voboostReleaseKeystoreFile ORG_GRADLE_PROJECT_voboostKeystorePassword; do
  [[ -z "${!name:-}" ]] || {
    echo "unsigned preparation rejects signing/debug input: $name" >&2
    exit 2
  }
done
[[ -f "${VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM:-}" ]] || {
  echo "VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM must name the source-pinned product public PEM" >&2
  exit 2
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
[[ -n "${ANDROID_HOME:-}" ]] || {
  echo "ANDROID_HOME is required" >&2
  exit 2
}
apksigner="${ANDROID_HOME}/build-tools/34.0.0/apksigner"
[[ -x "$apksigner" ]] || {
  echo "pinned apksigner is missing: $apksigner" >&2
  exit 2
}

build_once() {
  ./gradlew clean buildRelease --no-daemon -PvoboostUnsignedRelease=true
  local apk="build/outputs/apk/release/voboost-app.apk"
  [[ -f "$apk" ]] || {
    echo "unsigned APK output is missing" >&2
    exit 1
  }
  if "$apksigner" verify "$apk" >/dev/null 2>&1; then
    echo "unsigned preparation unexpectedly produced a signed APK" >&2
    exit 1
  fi
}

build_once
cp build/outputs/apk/release/voboost-app.apk "$tmp/first.apk"
build_once
cmp "$tmp/first.apk" build/outputs/apk/release/voboost-app.apk || {
  echo "unsigned APK build is not byte-deterministic" >&2
  exit 1
}
install -m 0644 build/outputs/apk/release/voboost-app.apk "$output"
