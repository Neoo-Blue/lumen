#!/usr/bin/env bash
# Build the Lumen APK on a fresh Ubuntu or Debian x86_64 machine.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${ROOT}"

if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  echo "curl and unzip are required" >&2
  exit 1
fi

java_major() {
  if ! command -v java >/dev/null 2>&1; then
    echo "0"
    return
  fi
  java -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

if [ "$(java_major)" != "17" ]; then
  if [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  else
    export DEBIAN_FRONTEND=noninteractive
    if [ "$(id -u)" -eq 0 ]; then
      apt-get update
      apt-get install -y openjdk-17-jdk-headless
    else
      sudo apt-get update
      sudo apt-get install -y openjdk-17-jdk-headless
    fi
    if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
      export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    else
      JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
      export JAVA_HOME
    fi
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
  hash -r || true
fi

if [ "$(java_major)" != "17" ]; then
  echo "Java 17 is required" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
  else
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    export JAVA_HOME
  fi
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

SDK_ROOT="${HOME}/android-sdk"
mkdir -p "${SDK_ROOT}"
SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "${SDKMANAGER}" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "${tmp}/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
  unzip -q "${tmp}/cmdtools.zip" -d "${tmp}/unzipped"
  mkdir -p "${SDK_ROOT}/cmdline-tools"
  rm -rf "${SDK_ROOT}/cmdline-tools/latest"
  if [ -x "${tmp}/unzipped/cmdline-tools/bin/sdkmanager" ]; then
    mv "${tmp}/unzipped/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
  elif [ -x "${tmp}/unzipped/cmdline-tools/latest/bin/sdkmanager" ]; then
    mv "${tmp}/unzipped/cmdline-tools/latest" "${SDK_ROOT}/cmdline-tools/latest"
  else
    echo "sdkmanager was not in the command-line tools archive" >&2
    rm -rf "${tmp}"
    exit 1
  fi
  rm -rf "${tmp}"
fi

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"

# sdkmanager prompts on stdin. pipefail is off so SIGPIPE from yes is not a failure.
set +e
set +o pipefail
yes | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null 2>&1
set -e
set -o pipefail

"${SDKMANAGER}" --sdk_root="${SDK_ROOT}" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

GRADLE_VERSION="8.10.2"
GRADLE_HOME="${HOME}/.gradle-dist/gradle-${GRADLE_VERSION}"
if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  tmp="$(mktemp -d)"
  mkdir -p "${HOME}/.gradle-dist"
  curl -fsSL -o "${tmp}/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q "${tmp}/gradle.zip" -d "${HOME}/.gradle-dist"
  rm -rf "${tmp}"
fi

escaped="$(printf '%s' "${SDK_ROOT}" | sed 's/\\/\\\\/g; s/:/\\:/g; s/ /\\ /g')"
printf 'sdk.dir=%s\n' "${escaped}" > "${ROOT}/local.properties"

"${GRADLE_HOME}/bin/gradle" --no-daemon assembleDebug

mkdir -p "${ROOT}/dist"
cp -f "${ROOT}/app/build/outputs/apk/debug/app-debug.apk" "${ROOT}/dist/lumen.apk"
echo "Built ${ROOT}/dist/lumen.apk"
