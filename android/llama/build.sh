#!/usr/bin/env bash
# Build a stripped arm64-v8a llama-server for the Lumen APK.
# Idempotent: a second run reuses the NDK and the pinned llama.cpp checkout.
# Pass --smoke to also build a host x86_64 server and prove tool calling.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TAG="v1.0.0"
NDK_REV="r29"
NDK_ZIP="android-ndk-${NDK_REV}-linux.zip"
NDK_URL="https://dl.google.com/android/repository/${NDK_ZIP}"
WORK="${ROOT}/.build"
SRC="${WORK}/llama.cpp"
NDK="${WORK}/android-ndk-${NDK_REV}"
ANDROID_BUILD="${WORK}/android"
HOST_BUILD="${WORK}/host"
OUT="${ROOT}/out/jniLibs/arm64-v8a"
META="${ROOT}/out"
SMOKE=0

if [[ "${1:-}" == "--smoke" ]]; then
    SMOKE=1
elif [[ -n "${1:-}" ]]; then
    echo "usage: $0 [--smoke]" >&2
    exit 2
fi

log() { printf '\n== %s\n' "$*"; }

if [[ "$(id -u)" -eq 0 ]]; then
    SUDO=""
else
    SUDO="sudo"
fi

log "packages"
if ! command -v cmake >/dev/null 2>&1 || ! command -v ninja >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1; then
    $SUDO apt-get update
    $SUDO apt-get install -y --no-install-recommends \
        ca-certificates cmake ninja-build git curl unzip python3 build-essential pkg-config
fi

mkdir -p "$WORK" "$OUT" "$META"

if [[ ! -f "${NDK}/build/cmake/android.toolchain.cmake" ]]; then
    log "NDK ${NDK_REV}"
    tmp="${WORK}/${NDK_ZIP}.partial"
    stage="${WORK}/ndk-src"
    curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$NDK_URL"
    rm -rf "$NDK" "$stage"
    mkdir -p "$stage"
    unzip -q "$tmp" -d "$stage"
    rm -f "$tmp"
    toolchain="$(find "$stage" -path '*/build/cmake/android.toolchain.cmake' -print -quit)"
    if [[ -z "$toolchain" ]]; then
        echo "NDK zip has no android.toolchain.cmake. Top entries:" >&2
        find "$stage" -maxdepth 2 -type d >&2
        exit 1
    fi
    # toolchain is <ndk>/build/cmake/android.toolchain.cmake
    found="$(cd "$(dirname "$toolchain")/../.." && pwd)"
    rm -rf "$NDK"
    mv "$found" "$NDK"
    rm -rf "$stage"
fi
if [[ ! -f "${NDK}/build/cmake/android.toolchain.cmake" ]]; then
    echo "NDK toolchain missing under ${NDK}" >&2
    exit 1
fi

PREBUILT="$(echo "${NDK}"/toolchains/llvm/prebuilt/*)"
STRIP="${PREBUILT}/bin/llvm-strip"
READELF="${PREBUILT}/bin/llvm-readelf"
if [[ ! -x "$STRIP" || ! -x "$READELF" ]]; then
    echo "llvm-strip or llvm-readelf missing in ${PREBUILT}" >&2
    exit 1
fi

LIBCXX="${PREBUILT}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
STL="c++_shared"
if [[ ! -f "$LIBCXX" ]]; then
    echo "libc++_shared.so missing, using c++_static" >&2
    STL="c++_static"
fi

if [[ ! -d "${SRC}/.git" ]]; then
    log "clone llama.cpp ${TAG}"
    rm -rf "$SRC"
    git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$SRC"
fi
got="$(git -C "$SRC" describe --tags --exact-match HEAD 2>/dev/null || true)"
if [[ "$got" != "$TAG" ]]; then
    echo "checkout at ${SRC} is '${got}', want tag ${TAG}" >&2
    exit 1
fi
COMMIT="$(git -C "$SRC" rev-parse HEAD)"
log "pinned ${TAG} ${COMMIT}"

# 16 KB ELF alignment plus the GNU_RELRO end-address rule:
# https://developer.android.com/guide/practices/page-sizes
# Do not put $ORIGIN here. Ninja treats $ as a variable, so -rpath,$ORIGIN
# swallows the next input file and the link fails with "undefined symbol: main".
# Sibling libs are found via LD_LIBRARY_PATH (see README).
PAGE_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now -Wl,--enable-new-dtags'

log "configure android (${STL}, dynamic CPU variants)"
cmake -S "$SRC" -B "$ANDROID_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DANDROID_STL="${STL}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SKIP_RPATH=ON \
    -DCMAKE_EXE_LINKER_FLAGS="${PAGE_FLAGS}" \
    -DCMAKE_SHARED_LINKER_FLAGS="${PAGE_FLAGS}" \
    -DCMAKE_MODULE_LINKER_FLAGS="${PAGE_FLAGS}" \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_BACKEND_DL=ON \
    -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_CPU_KLEIDIAI=OFF \
    -DGGML_CCACHE=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DLLAMA_LLGUIDANCE=OFF

log "build llama-server"
cmake --build "$ANDROID_BUILD" --target llama-server -j 2

log "collect and strip"
rm -rf "${OUT:?}/"*
install -m 0755 "${ANDROID_BUILD}/bin/llama-server" "${OUT}/libllama_server.so"
shopt -s nullglob
for so in "${ANDROID_BUILD}/bin/"*.so "${ANDROID_BUILD}/bin/"*.so.*; do
    base="$(basename "$so")"
    case "$base" in
        *.so|*.so.*) cp -a "$so" "${OUT}/${base}" ;;
    esac
done
shopt -u nullglob
if [[ "$STL" == "c++_shared" ]]; then
    cp -a "$LIBCXX" "${OUT}/libc++_shared.so"
fi
for f in "${OUT}/"*; do
    "$STRIP" --strip-unneeded "$f"
    chmod 0755 "$f"
done

python3 - "$READELF" "$OUT" <<'PY'
import os, subprocess, sys
readelf, out = sys.argv[1], sys.argv[2]
page = 0x4000
system = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libstdc++.so", "ld-android.so",
}

def headers(path):
    text = subprocess.check_output([readelf, "-Wl", path], text=True, errors="replace")
    loads, relros = [], []
    for line in text.splitlines():
        tok = line.split()
        if len(tok) < 7 or tok[0] not in ("LOAD", "GNU_RELRO"):
            continue
        rec = {
            "type": tok[0],
            "virt": int(tok[2], 16),
            "memsz": int(tok[5], 16),
            "align": int(tok[-1], 16),
            "raw": line.strip(),
        }
        (loads if tok[0] == "LOAD" else relros).append(rec)
    return text, loads, relros

def relro_ok(loads, relros):
    if not relros:
        return True, "no GNU_RELRO"
    reasons = []
    for rel in relros:
        end = rel["virt"] + rel["memsz"]
        if end % page == 0:
            reasons.append(f"RELRO end 0x{end:x} aligned")
            continue
        contained = [
            ld for ld in loads
            if ld["virt"] <= rel["virt"] and rel["virt"] + rel["memsz"] <= ld["virt"] + ld["memsz"]
        ]
        if any(ld["virt"] + ld["memsz"] == end for ld in contained):
            reasons.append(f"RELRO end 0x{end:x} fills its LOAD")
            continue
        return False, f"GNU_RELRO end 0x{end:x} is not 16 KB aligned and writable data follows it in the same LOAD"
    return True, "; ".join(reasons)

failed = False
summary = []
for name in sorted(os.listdir(out)):
    path = os.path.join(out, name)
    if not os.path.isfile(path):
        continue
    text, loads, relros = headers(path)
    head = subprocess.check_output([readelf, "-h", path], text=True, errors="replace")
    if "AArch64" not in head:
        print(f"FAIL {name}: not AArch64")
        failed = True
    if not loads:
        print(f"FAIL {name}: no LOAD segments")
        failed = True
        continue
    for ld in loads:
        if ld["align"] < page:
            print(f"FAIL {name}: LOAD align 0x{ld['align']:x}")
            print(ld["raw"])
            failed = True
    ok, why = relro_ok(loads, relros)
    if not ok:
        print(f"FAIL {name}: {why}")
        failed = True
    needed = subprocess.check_output([readelf, "-d", path], text=True, errors="replace")
    missing = []
    for line in needed.splitlines():
        if "(NEEDED)" not in line:
            continue
        lib = line.split("[", 1)[1].split("]", 1)[0]
        if lib in system:
            continue
        if not os.path.exists(os.path.join(out, lib)):
            missing.append(lib)
    if missing:
        print(f"FAIL {name}: DT_NEEDED not in folder: {', '.join(missing)}")
        failed = True
    secs = subprocess.check_output([readelf, "-S", path], text=True, errors="replace")
    if ".debug_info" in secs or ".debug_line" in secs:
        print(f"FAIL {name}: debug sections remain after llvm-strip")
        failed = True
    size = os.path.getsize(path)
    print(f"OK {name} {size} bytes LOAD={[hex(ld['align']) for ld in loads]} RELRO: {why}")
    summary.append((name, size, why))
    if name == "libllama_server.so":
        open(os.path.join(os.path.dirname(out), "readelf-libllama_server.txt"), "w").write(text)

if not os.path.exists(os.path.join(out, "libllama_server.so")):
    print("FAIL missing libllama_server.so")
    failed = True
if not any(n.startswith("libggml-cpu-") for n, _, _ in summary):
    print("FAIL no libggml-cpu-* variant backend was produced")
    failed = True
if failed:
    sys.exit(1)
print(f"alignment checks passed for {len(summary)} files")
PY

log "sizes"
ls -lh "$OUT"
{
    echo "tag=${TAG}"
    echo "commit=${COMMIT}"
    echo "ndk=${NDK_REV}"
    echo "abi=arm64-v8a"
    echo "platform=android-30"
    echo "stl=${STL}"
    echo "build_shared_libs=ON"
    echo "ggml_backend_dl=ON"
    echo "ggml_cpu_all_variants=ON"
    echo "ggml_native=OFF"
    echo "ggml_openmp=OFF"
    echo "ggml_llamafile=OFF"
    echo "ggml_vulkan=OFF"
    echo "llama_curl=OFF"
    echo "llama_openssl=OFF"
    echo "page_flags=${PAGE_FLAGS}"
    echo
    echo "files:"
    find "$OUT" -type f -printf '%s %p\n' | sort -k2
} > "${META}/BUILD-INFO.txt"

if [[ "$SMOKE" -ne 1 ]]; then
    log "android build done"
    exit 0
fi

log "free disk before host build and model download"
df -h "$WORK" "$ROOT" || df -h
rm -rf "$ANDROID_BUILD" "$NDK" "${WORK}/${NDK_ZIP}" "${WORK}/${NDK_ZIP}.partial"

log "configure host x86_64"
cmake -S "$SRC" -B "$HOST_BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_NATIVE=ON \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_CCACHE=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DLLAMA_LLGUIDANCE=OFF
cmake --build "$HOST_BUILD" --target llama-server -j "$(nproc)"
HOST_BIN="${HOST_BUILD}/bin/llama-server"
[[ -x "$HOST_BIN" ]]

log "flag check from host --help"
help="$("$HOST_BIN" --help 2>&1 || true)"
printf '%s\n' "$help" > "${META}/llama-server-help.txt"
need=(
    --host --port --api-key --jinja --reasoning --mmproj
    --ctx-size --cache-type-k --cache-type-v --flash-attn
    --threads --no-webui --chat-template-kwargs --no-warmup
)
for flag in "${need[@]}"; do
    if ! grep -q -F -- "$flag" <<<"$help"; then
        echo "host --help is missing ${flag}" >&2
        exit 1
    fi
    echo "help has ${flag}"
done

MODELS="${WORK}/models"
mkdir -p "$MODELS"
download_check() {
    local url="$1" dest="$2" expect="$3"
    if [[ ! -f "$dest" ]] || ! echo "${expect}  ${dest}" | sha256sum -c -; then
        rm -f "$dest"
        curl -fL --retry 3 --retry-delay 2 -A "lumen-llama-build" -o "$dest" "$url"
    fi
    echo "${expect}  ${dest}" | sha256sum -c -
    local got
    got="$(stat -c %s "$dest")"
    echo "${dest} ${got} bytes"
}

log "hash 4B mmproj then delete it"
download_check \
    "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/mmproj-F16.gguf" \
    "${MODELS}/mmproj-4b-F16.gguf" \
    "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864"
rm -f "${MODELS}/mmproj-4b-F16.gguf"

log "download 2B model and mmproj"
download_check \
    "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf" \
    "${MODELS}/mmproj-F16.gguf" \
    "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7"
download_check \
    "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf" \
    "${MODELS}/Qwen3.5-2B-Q4_K_M.gguf" \
    "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"

# Drop the host object tree's bulk by keeping only the linked binary.
cp -a "$HOST_BIN" "${WORK}/llama-server-host"
rm -rf "$HOST_BUILD"
HOST_BIN="${WORK}/llama-server-host"

KEY="smoke-local-key"
PORT=47130
LOG="${META}/smoke-server.log"
rm -f "$LOG"
log "start host llama-server"
"$HOST_BIN" \
    --host 127.0.0.1 \
    --port "$PORT" \
    --api-key "$KEY" \
    --jinja \
    --reasoning off \
    -m "${MODELS}/Qwen3.5-2B-Q4_K_M.gguf" \
    --mmproj "${MODELS}/mmproj-F16.gguf" \
    -c 2048 \
    -ctk q8_0 \
    -ctv q8_0 \
    -fa on \
    --threads "$(nproc)" \
    --no-webui \
    --no-warmup \
    >"$LOG" 2>&1 &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; }
trap cleanup EXIT

ready=0
for _ in $(seq 1 240); do
    if curl -sf -H "Authorization: Bearer ${KEY}" "http://127.0.0.1:${PORT}/health" >/dev/null; then
        ready=1
        break
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "llama-server exited during startup" >&2
        tail -n 120 "$LOG" >&2 || true
        exit 1
    fi
    sleep 2
done
if [[ "$ready" -ne 1 ]]; then
    echo "llama-server did not become healthy" >&2
    tail -n 120 "$LOG" >&2 || true
    exit 1
fi

log "tool-call request"
body="${WORK}/request.json"
cat > "$body" <<'JSON'
{
  "messages": [
    {"role": "user", "content": "What is the weather in Paris right now? Call the get_weather tool."}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get the current weather for a city",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {"type": "string", "description": "City name"}
          },
          "required": ["city"]
        }
      }
    }
  ],
  "tool_choice": "required",
  "chat_template_kwargs": {"enable_thinking": false},
  "temperature": 0,
  "max_tokens": 128
}
JSON

curl -sf --max-time 300 \
    -H "Authorization: Bearer ${KEY}" \
    -H "Content-Type: application/json" \
    -d @"$body" \
    "http://127.0.0.1:${PORT}/v1/chat/completions" \
    -o "${META}/smoke-response.json"

python3 - "${META}/smoke-response.json" "$LOG" <<'PY'
import json, sys
path, log_path = sys.argv[1], sys.argv[2]
raw = json.load(open(path))
choice = (raw.get("choices") or [{}])[0]
msg = choice.get("message") or {}
calls = msg.get("tool_calls") or []
trim = {
    "model": raw.get("model"),
    "finish_reason": choice.get("finish_reason"),
    "message": {
        "role": msg.get("role"),
        "content": msg.get("content"),
        "tool_calls": calls,
    },
    "usage": raw.get("usage"),
    "timings": raw.get("timings"),
}
open(path, "w").write(json.dumps(trim, indent=2) + "\n")
print(json.dumps(trim, indent=2))
if not calls:
    print("FAIL: response has no tool_calls", file=sys.stderr)
    sys.exit(1)
name = ((calls[0].get("function") or {}).get("name"))
print(f"tool_calls={len(calls)} first={name}")
timings = raw.get("timings") or {}
tps = timings.get("predicted_per_second")
if tps is None:
    text = open(log_path, errors="replace").read().splitlines()
    for line in text:
        if "tokens per second" in line:
            print(line)
else:
    print(f"predicted_per_second={tps}")
PY

log "smoke passed"
trap - EXIT
cleanup
exit 0
