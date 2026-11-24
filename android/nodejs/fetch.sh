#!/usr/bin/env bash
# Stage the pinned nodejs-mobile Android build (Node 24.5.0, full-icu).
# Idempotent: a second run does nothing when libnode.so, the headers, and the
# stamp all match the sha256 below.
#
# CI line to add to ci-build.sh immediately before gradle assembleDebug:
#   bash "${ROOT}/nodejs/fetch.sh"
#
# libnode.so is large. A .gitignore in this directory cannot match a file
# outside it. Add this line to the repo-root .gitignore (lumen-oss/.gitignore):
#   android/app/src/main/jniLibs/arm64-v8a/libnode.so
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

URL="https://github.com/capawesome-team/nodejs-mobile/releases/download/v24.5.0-capawesome.1/nodejs-mobile-v24.5.0-capawesome.1-android.zip"
SHA256="1aba9dc5f9df5aed0b4fbfdb9b23252b4b5642fbc6df0e53a777669bbb5cc3b5"

CACHE="${ROOT}/nodejs/.cache"
ZIP="${CACHE}/nodejs-mobile-v24.5.0-capawesome.1-android.zip"
LIB_OUT="${ROOT}/app/src/main/jniLibs/arm64-v8a/libnode.so"
INC_OUT="${ROOT}/nodejs/include"
STAMP="${INC_OUT}/.fetch-stamp"

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

stamp_matches() {
  [[ -f "${STAMP}" && -f "${LIB_OUT}" && -f "${INC_OUT}/node/node.h" ]] || return 1
  [[ "$(tr -d '[:space:]' < "${STAMP}")" == "${SHA256}" ]]
}

if stamp_matches; then
  echo "nodejs fetch: up to date (${SHA256})"
  exit 0
fi

mkdir -p "${CACHE}"
if [[ ! -f "${ZIP}" ]] || [[ "$(hash_file "${ZIP}")" != "${SHA256}" ]]; then
  tmp="${ZIP}.partial"
  echo "nodejs fetch: downloading ${URL}"
  curl -fL --retry 5 --retry-delay 2 -o "${tmp}" "${URL}"
  actual="$(hash_file "${tmp}")"
  if [[ "${actual}" != "${SHA256}" ]]; then
    echo "nodejs fetch: sha256 mismatch" >&2
    echo "  expected ${SHA256}" >&2
    echo "  actual   ${actual}" >&2
    rm -f "${tmp}"
    exit 1
  fi
  mv -f "${tmp}" "${ZIP}"
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
unzip -q "${ZIP}" -d "${work}"

lib_src="$(find "${work}" -type f -path '*/bin/arm64-v8a/libnode.so' -print -quit)"
inc_src=""
while IFS= read -r dir; do
  if [[ -f "${dir}/node/node.h" ]]; then
    inc_src="${dir}"
    break
  fi
done < <(find "${work}" -type d -name include)

if [[ -z "${lib_src}" || -z "${inc_src}" ]]; then
  echo "nodejs fetch: archive is missing bin/arm64-v8a/libnode.so or include/node/node.h" >&2
  find "${work}" -maxdepth 4 -type d -o -name 'libnode.so' >&2
  exit 1
fi

mkdir -p "$(dirname "${LIB_OUT}")"
cp -f "${lib_src}" "${LIB_OUT}"
rm -rf "${INC_OUT}"
mkdir -p "${INC_OUT}"
cp -R "${inc_src}/." "${INC_OUT}/"
printf '%s\n' "${SHA256}" > "${STAMP}"

if ! stamp_matches; then
  echo "nodejs fetch: staged files failed the stamp check" >&2
  exit 1
fi

echo "nodejs fetch: staged ${LIB_OUT}"
echo "nodejs fetch: sha256 ${SHA256}"
