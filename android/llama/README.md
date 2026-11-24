# llama-server for the Lumen APK

Pinned source is llama.cpp tag `v1.0.0`. `build.sh` cross-compiles the `llama-server` target for `arm64-v8a` at `android-30` with NDK r29, strips it, and writes `out/jniLibs/arm64-v8a/`. Copy that directory to `app/src/main/jniLibs/arm64-v8a/`.

The binary is `libllama_server.so`. CPU kernels ship as separate `libggml-cpu-android_*.so` modules. A single static binary cannot carry `GGML_CPU_ALL_VARIANTS` (that option requires `GGML_BACKEND_DL` and shared libraries). At runtime ggml loads the newest variant the CPU can run: `android_armv8.0_1` on older armv8 devices, up through dotprod and i8mm (`android_armv8.6_1` / `android_armv9.0_1`) on a Snapdragon 8 Gen 2. Vulkan is off. The NDK does not ship the shader compiler the Vulkan backend needs, and the CPU path does not require it.

`build.sh --smoke` also builds a host binary from the same tag and checks tool calling. Android artifacts are still produced without that flag.

## Launch

The process working directory does not matter. Set `LD_LIBRARY_PATH` to `ApplicationInfo.nativeLibraryDir` before `ProcessBuilder.start()`. Every `lib*.so` from this folder must be in that directory under the names produced here. The loader opens `libllama.so`, `libggml.so`, `libggml-base.so`, `libmtmd.so`, and `libc++_shared.so` by SONAME through that path. It finds `libggml-cpu-*.so` by scanning the executable's own directory (`/proc/self/exe`), which is the same folder when the app starts the binary from `nativeLibraryDir`.

```
libllama_server.so \
  --host 127.0.0.1 \
  --port 47130 \
  --api-key <random> \
  --jinja \
  --reasoning off \
  -m <model.gguf> \
  --mmproj <mmproj.gguf> \
  -c 8192 \
  -ctk q8_0 \
  -ctv q8_0 \
  -fa on \
  --threads <n> \
  --no-webui
```

`<n>` should be the big-core count (4 to 6 on an 8 Gen 2), not every core. `--jinja` is what turns on the chat template, including tool calls. `--mmproj` loads the matching `mmproj-F16.gguf` from `models.json` so image parts can be sent on `/v1/chat/completions`.

## Qwen3.5 thinking

`--reasoning off` is the server switch. It sets the template kwarg `enable_thinking` to false for every request. `--chat-template-kwargs '{"enable_thinking":false}'` still exists and still applies, and this tag prints a deprecation warning that points at `--reasoning`.

A single request can override it:

```json
"chat_template_kwargs": {"enable_thinking": false}
```

Send the API key as `Authorization: Bearer <random>`.

## Models

`models.json` lists the two Unsloth GGUF choices (Q4_K_M weights plus F16 mmproj), with `resolve/main` URLs, byte sizes, and SHA-256.
