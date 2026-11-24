# Voice providers

Lumen speaks and listens through one provider, chosen in the dashboard. The server holds the key: the
browser talks only to Lumen's own `/listen` and `/tts`. Code: `voice.mjs`; tests: `node voice.test.mjs`.

| Provider | Live captions (STT) | Speech (TTS) | Paste in the dashboard | Get the key |
|---|---|---|---|---|
| Cartesia | Streaming, `ink-whisper` | MP3, `sonic-3` | API key | https://play.cartesia.ai/keys |
| Deepgram | Streaming, `nova-3` | MP3, `aura-2-thalia-en` | API key | https://console.deepgram.com |
| ElevenLabs | Streaming, `scribe_v2_realtime` | MP3, `eleven_flash_v2_5` | API key | https://elevenlabs.io/app/settings/api-keys |
| OpenRouter | Batch only: words appear when you stop talking | MP3, `openai/gpt-4o-mini-tts-2025-12-15` | API key | https://openrouter.ai/settings/keys |
| LiveKit Inference | Streaming, `deepgram/nova-3` (any LiveKit STT model) | WAV, not MP3, `cartesia/sonic-3` | API key and API secret (gateway URL optional) | https://cloud.livekit.io, project settings, API keys |

## Notes and limits

- **Every session**: at most 10 minutes and 30 MB of audio, then Lumen closes it with an error.
- **Cartesia**: the server authenticates with `X-API-Key`; the page no longer needs an access token. The page's `done` is sent as Cartesia's `close`.
- **Deepgram**: the voice is the TTS model name (`aura-2-<voice>-<lang>`); `/voices` lists them from `GET /v1/models`. Deepgram now recommends Flux TTS on `/v2/speak` for new work; Aura-2 on `/v1/speak` is still documented and is what Lumen uses. MP3 output is 22.05 kHz.
- **ElevenLabs**: commits manually when you stop talking. `eleven_multilingual_v2` takes no language code, so Lumen leaves it out for that model.
- **OpenRouter**: no streaming transcription; Lumen sends the recording as one WAV when you stop. TTS voices depend on the model (`alloy` etc. for OpenAI); there is no voice listing API, so `/voices` is empty.
- **LiveKit Inference**: needs a LiveKit Cloud project. Lumen signs a 10-minute token from the key and secret. TTS is WebSocket only and returns PCM, so Lumen serves `audio/wav`. Voice for `cartesia/sonic-3` is a Cartesia voice id. No voice listing API.

Docs: [Cartesia STT](https://docs.cartesia.ai/api-reference/stt/stt) ·
[Deepgram live](https://developers.deepgram.com/reference/speech-to-text/listen-streaming) ·
[Deepgram speak](https://developers.deepgram.com/reference/text-to-speech/speak-request) ·
[ElevenLabs realtime STT](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime) ·
[ElevenLabs TTS](https://elevenlabs.io/docs/api-reference/text-to-speech/convert) ·
[OpenRouter transcription](https://openrouter.ai/docs/api/api-reference/stt/create-transcription) ·
[OpenRouter speech](https://openrouter.ai/docs/api/api-reference/tts/create-speech) ·
[LiveKit Inference (agents source)](https://github.com/livekit/agents/tree/main/livekit-agents/livekit/agents/inference)
