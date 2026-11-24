#!/usr/bin/env bash
# Build tsbridge.aar on a fresh Linux sandbox (e2bx). Installs Go (the version
# tailscale's go.mod requires), a JDK, the Android SDK platform, NDK r28+,
# and gomobile when they are missing. Also used as `./build.sh test` to run
# the unit tests once Go is installed.
set -euo pipefail
cd "$(dirname "$0")"

GO_VERSION=1.27.4
NDK_REV=r28c
ANDROID_API=30
PREFIX="${TSBRIDGE_PREFIX:-${HOME}/.tsbridge-toolchain}"
GO_ROOT="${PREFIX}/go"
SDK_ROOT="${PREFIX}/android-sdk"
NDK_ROOT="${PREFIX}/android-ndk-${NDK_REV}"

export DEBIAN_FRONTEND=noninteractive
export GOTOOLCHAIN=local
export GOMAXPROCS="${GOMAXPROCS:-2}"
export GOMEMLIMIT="${GOMEMLIMIT:-6GiB}"
export GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}"

log() { printf 'tsbridge-build: %s\n' "$*"; }

apt_install() {
  if [[ "$(id -u)" -eq 0 ]]; then
    apt-get update
    apt-get install -y --no-install-recommends "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y --no-install-recommends "$@"
  else
    log "need root or sudo to install: $*"
    exit 1
  fi
}

need_cmd() {
  local cmd="$1"
  shift
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "installing packages for missing ${cmd}"
    apt_install "$@"
  fi
}

install_go() {
  if [[ -x "${GO_ROOT}/bin/go" ]] && "${GO_ROOT}/bin/go" version | grep -q "go${GO_VERSION} "; then
    return
  fi
  local arch goarch
  arch="$(uname -m)"
  case "$arch" in
    x86_64) goarch=amd64 ;;
    aarch64|arm64) goarch=arm64 ;;
    *) log "unsupported arch ${arch}"; exit 1 ;;
  esac
  log "installing Go ${GO_VERSION} (${goarch})"
  mkdir -p "${PREFIX}"
  curl -fL --retry 3 --retry-delay 2 -o /tmp/go.tgz "https://go.dev/dl/go${GO_VERSION}.linux-${goarch}.tar.gz"
  rm -rf "${GO_ROOT}"
  tar -C "${PREFIX}" -xzf /tmp/go.tgz
  # The tarball unpacks to a directory named "go".
  if [[ "${PREFIX}/go" != "${GO_ROOT}" ]]; then
    mv "${PREFIX}/go" "${GO_ROOT}"
  fi
}

export_go_path() {
  local gopath
  gopath="$("${GO_ROOT}/bin/go" env GOPATH)"
  export PATH="${GO_ROOT}/bin:${gopath}/bin:${PATH}"
  export GOTOOLCHAIN=local
}

ndk_revision() {
  local root="$1"
  [[ -f "${root}/source.properties" ]] || return 1
  awk -F= '/^Pkg.Revision/ { gsub(/ /,"",$2); print $2; exit }' "${root}/source.properties"
}

ndk_is_r28_plus() {
  local rev major
  rev="$(ndk_revision "$1" || true)"
  major="${rev%%.*}"
  [[ -n "$major" && "$major" -ge 28 ]]
}

host_prebuilt() {
  case "$(uname -m)" in
    aarch64|arm64) printf '%s\n' linux-aarch64 ;;
    *) printf '%s\n' linux-x86_64 ;;
  esac
}

find_existing_ndk() {
  local cand
  if [[ -n "${ANDROID_NDK_HOME:-}" ]] && ndk_is_r28_plus "${ANDROID_NDK_HOME}"; then
    printf '%s\n' "${ANDROID_NDK_HOME}"
    return 0
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    local best=""
    for cand in "${ANDROID_HOME}/ndk"/*; do
      [[ -d "$cand" ]] || continue
      if ndk_is_r28_plus "$cand"; then
        best="$cand"
      fi
    done
    if [[ -n "$best" ]]; then
      printf '%s\n' "$best"
      return 0
    fi
  fi
  if ndk_is_r28_plus "${NDK_ROOT}"; then
    printf '%s\n' "${NDK_ROOT}"
    return 0
  fi
  return 1
}

install_ndk() {
  if NDK_FOUND="$(find_existing_ndk)"; then
    NDK_ROOT="${NDK_FOUND}"
    log "using NDK ${NDK_ROOT} ($(ndk_revision "${NDK_ROOT}" || echo unknown))"
    return
  fi
  local arch zipname
  case "$(uname -m)" in
    aarch64|arm64) zipname="android-ndk-${NDK_REV}-linux.zip" ;;
    *) zipname="android-ndk-${NDK_REV}-linux.zip" ;;
  esac
  # The published linux zip is the x86_64 host toolchain. An aarch64 host
  # falls through only if a preinstalled r28+ NDK was not found above.
  if [[ "$(uname -m)" != "x86_64" ]]; then
    log "no r28+ NDK for host $(uname -m); the r28c zip is an x86_64 toolchain"
    exit 1
  fi
  log "downloading Android NDK ${NDK_REV}"
  curl -fL --retry 3 --retry-delay 2 -o /tmp/ndk.zip "https://dl.google.com/android/repository/${zipname}"
  rm -rf "${NDK_ROOT}"
  mkdir -p "${PREFIX}"
  unzip -q /tmp/ndk.zip -d "${PREFIX}"
  if [[ ! -d "${NDK_ROOT}" ]]; then
    log "NDK zip did not contain ${NDK_ROOT}"
    ls -la "${PREFIX}"
    exit 1
  fi
  log "installed NDK $(ndk_revision "${NDK_ROOT}")"
}

install_sdk_platform() {
  local home="${ANDROID_HOME:-${SDK_ROOT}}"
  if [[ -f "${home}/platforms/android-${ANDROID_API}/android.jar" ]]; then
    export ANDROID_HOME="${home}"
    log "using Android SDK ${ANDROID_HOME}"
    return
  fi
  export ANDROID_HOME="${SDK_ROOT}"
  mkdir -p "${ANDROID_HOME}/cmdline-tools"
  if [[ ! -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "downloading Android command line tools"
    local url ok=0
    for url in \
      "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    do
      if curl -fL --retry 3 --retry-delay 2 -o /tmp/cmdtools.zip "$url"; then
        ok=1
        break
      fi
    done
    if [[ "$ok" -ne 1 ]]; then
      log "could not download Android command line tools"
      exit 1
    fi
    rm -rf /tmp/cmdtools
    mkdir -p /tmp/cmdtools
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools
    rm -rf "${ANDROID_HOME}/cmdline-tools/latest"
    mv /tmp/cmdtools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest"
  fi
  local sdkmanager="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
  yes | "${sdkmanager}" --sdk_root="${ANDROID_HOME}" --licenses >/tmp/sdk-licenses.log || true
  log "installing platforms;android-${ANDROID_API}"
  "${sdkmanager}" --sdk_root="${ANDROID_HOME}" "platforms;android-${ANDROID_API}"
}

install_gomobile() {
  # Go 1.24+ gomobile bind checks that golang.org/x/mobile/bind resolves in
  # this module. A tool directive survives a later go mod tidy; a plain
  # require does not. Install the same selected versions onto PATH.
  log "adding golang.org/x/mobile as a tool dependency"
  go get -tool golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
  log "installing gomobile and gobind"
  go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
  command -v gomobile
  command -v gobind
  gomobile version
}

confirm_androidbin() {
  log "tailscale module: $(go list -m tailscale.com)"
  go list -f 'androidbin dir={{.Dir}} files={{.GoFiles}}' tailscale.com/feature/androidbin
  GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -f 'android/arm64 files={{.GoFiles}} cgo={{.CgoFiles}}' tailscale.com/feature/androidbin
}

omit_tags() {
  local mod dir tags=()
  mod="$(go list -m -f '{{.Dir}}' tailscale.com)"
  if grep -R -q --include='*.go' 'ts_omit_ssh' "$mod"; then
    tags+=(ts_omit_ssh)
  else
    log "ts_omit_ssh is not present in this tailscale module"
  fi
  if grep -R -q --include='*.go' 'ts_omit_taildrop' "$mod"; then
    tags+=(ts_omit_taildrop)
  else
    log "ts_omit_taildrop is not present in this tailscale module"
  fi
  local IFS=','
  printf '%s\n' "${tags[*]}"
}

write_notice() {
  local cache
  cache="$(go env GOMODCACHE)"
  GOMODCACHE="$cache" python3 - <<'PY'
import glob, os
from pathlib import Path

cache = os.environ["GOMODCACHE"]

def newest(pattern):
    matches = sorted(glob.glob(os.path.join(cache, pattern)))
    return matches[-1] if matches else ""

def license_file(mod_dir):
    for name in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "COPYING.md"):
        p = os.path.join(mod_dir, name)
        if os.path.isfile(p):
            return p
    return ""

sections = []

def add(heading, mod_dir):
    if not mod_dir:
        raise SystemExit(f"missing module for {heading}")
    path = license_file(mod_dir)
    if not path:
        raise SystemExit(f"no LICENSE in {mod_dir}")
    text = Path(path).read_text(errors="replace").strip()
    mod = os.path.relpath(mod_dir, cache)
    sections.append(f"{heading}\nModule: {mod}\n\n{text}")

add("Tailscale (BSD-3-Clause)", newest("tailscale.com@*"))
add("gVisor (Apache-2.0)", newest("gvisor.dev/gvisor@*"))

wg = newest("github.com/tailscale/wireguard-go@*")
if not wg:
    wg = newest("golang.zx2c4.com/wireguard@*")
add("wireguard-go (MIT)", wg)

body = (
    "Third-party notices for github.com/lumen-assistant/tsbridge\n"
    "\n"
    "This archive embeds Tailscale (userspace tsnet), the gVisor network\n"
    "stack, and wireguard-go. The license texts below are copied from the\n"
    "LICENSE files of those modules.\n"
    "\n"
    + ("\n\n" + ("-" * 72) + "\n\n").join(sections)
    + "\n"
)
Path("NOTICE").write_text(body)
print("wrote NOTICE")
PY
}

verify_alignment() {
  local prebuilt so readelf
  prebuilt="$(host_prebuilt)"
  readelf="${NDK_ROOT}/toolchains/llvm/prebuilt/${prebuilt}/bin/llvm-readelf"
  if [[ ! -x "$readelf" ]]; then
    log "llvm-readelf not at ${readelf}"
    find "${NDK_ROOT}/toolchains/llvm/prebuilt" -name llvm-readelf -type f | head
    exit 1
  fi
  rm -rf /tmp/tsbridge-aar
  mkdir -p /tmp/tsbridge-aar
  unzip -q out/tsbridge.aar -d /tmp/tsbridge-aar
  so="$(find /tmp/tsbridge-aar -name '*.so' -print | head -n 1)"
  if [[ -z "$so" ]]; then
    log "aar contains no .so"
    find /tmp/tsbridge-aar -type f
    exit 1
  fi
  log "checking LOAD alignment of ${so}"
  "$readelf" -l "$so" | tee /tmp/tsbridge-readelf.txt
  python3 - <<'PY'
from pathlib import Path
import sys
text = Path("/tmp/tsbridge-readelf.txt").read_text()
aligns = []
for line in text.splitlines():
    parts = line.split()
    if len(parts) < 2 or parts[0] != "LOAD":
        continue
    align = parts[-1]
    if not align.startswith("0x"):
        sys.exit(f"could not parse LOAD line: {line}")
    aligns.append(int(align, 16))
if not aligns:
    sys.exit("no LOAD segments")
print("LOAD aligns:", " ".join(hex(a) for a in aligns))
if min(aligns) < 0x4000:
    sys.exit("LOAD alignment is below 16 KB (0x4000)")
print("16 KB alignment: ok")
PY
  local size
  size="$(wc -c < out/tsbridge.aar | tr -d ' ')"
  log "aar bytes: ${size}"
  ls -lh out/tsbridge.aar
}

prepare_modules() {
  log "resolving modules"
  go mod tidy
  confirm_androidbin
  write_notice
}

main() {
  local mode="${1:-build}"
  need_cmd curl curl ca-certificates
  need_cmd python3 python3
  install_go
  export_go_path
  go version
  prepare_modules

  if [[ "$mode" == "test" ]]; then
    log "running unit tests"
    go test -count=1 ./...
    exit 0
  fi

  need_cmd unzip unzip
  if ! command -v javac >/dev/null 2>&1; then
    apt_install openjdk-21-jdk-headless || apt_install openjdk-17-jdk-headless
  fi
  command -v javac
  javac -version

  install_ndk
  export ANDROID_NDK_HOME="${NDK_ROOT}"
  install_sdk_platform
  export ANDROID_HOME
  install_gomobile

  local tags
  tags="$(omit_tags)"
  log "gomobile tags: ${tags:-<none>}"
  # gVisor packages are large. One compile at a time stays inside 8 GB.
  export GOMAXPROCS=1
  mkdir -p out
  # shellcheck disable=SC2086
  gomobile bind \
    -target=android/arm64 \
    -androidapi "${ANDROID_API}" \
    -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" \
    ${tags:+-tags "$tags"} \
    -o out/tsbridge.aar \
    .
  verify_alignment
}

main "$@"
