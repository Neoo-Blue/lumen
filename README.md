# Lumen

**Your own AI assistant for Android, running on your own server.**

Lumen replaces Google Assistant on your phone. It does things for you, not just answers: it opens apps, changes
settings, reads and taps the screen, fills in forms, searches your messages, runs routines, and can call businesses
for you. Its brain runs on a server you own, and your phone reaches that server over a private network built into the
app. Nothing is open to the internet, and nothing passes through anyone else's servers except the AI model you choose.

To our knowledge, Lumen is the first self-hosted Android assistant that ships with private networking built in: no
separate VPN app, no port forwarding, no third-party relay. (Other projects replace the assistant or control the
screen, and some run their brain on your server; you connect them to it yourself.)

<p align="center">
  <img src="docs/screenshots/day.png" width="24%" alt="Your day as a timeline, with one-tap follow-ups">
  <img src="docs/screenshots/flights.png" width="24%" alt="Live flight options, cheapest first">
  <img src="docs/screenshots/form-review.png" width="24%" alt="Reviewing every box of a booking form before it is filled in">
  <img src="docs/screenshots/confirm.png" width="24%" alt="A text message waiting for your OK">
</p>
<p align="center"><sub>Screenshots use made-up demo data.</sub></p>

> **Status: early.** Lumen works every day on its author's phone, but it is new as a self-hosted project. It has been
> tested on one phone model (a Samsung Galaxy Z Flip on Android 17). Expect rough edges, and please report them.

## Two ways to use it

| | Hosted | Self-hosted |
|---|---|---|
| Where it runs | Our servers, at **[lumen.cooli.ai](https://lumen.cooli.ai)** | Your own server (this repository) |
| Setup | Sign in and install the app. Nothing else. | About ten minutes: see [Set it up](#set-it-up) |
| Your data | Kept on our servers, private to you | Never leaves your server, except what the AI model you choose needs |
| AI model | Included | Bring your own key or subscription |
| Android app | [Lumen One](https://lumen.cooli.ai/app/lumen-one.apk) | [Lumen](https://github.com/Neoo-Blue/lumen/releases/latest) |

**Just want to try it?** Open [lumen.cooli.ai](https://lumen.cooli.ai) on your phone, sign in, and install the app it
offers. The hosted version is the same assistant and gets new features first.

**Want it fully yours?** Everything below is about running your own.

---

## Contents

- [Set it up](#set-it-up)
- [What it can do](#what-it-can-do)
- [What it can't do (yet)](#what-it-cant-do-yet)
- [What's coming](#whats-coming)
- [Privacy and safety](#privacy-and-safety)
- [How it fits together](#how-it-fits-together)
- [Requirements](#requirements)
- [FAQ](#faq)
- [License](#license)

---

## Set it up

You need an Android phone, a computer that stays on, a free [Tailscale](https://tailscale.com) account, and an API key
for the AI model you want. About ten minutes.

**1. Start the server.** Pick one:

| Where | Command |
|---|---|
| Docker (Linux, NAS, mini PC) | `curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-docker.sh \| bash` |
| Proxmox (makes a new container) | `bash -c "$(curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-lxc.sh)"` |
| Debian or Ubuntu | `curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-debian.sh \| sudo bash` |

<p align="center">
  <img src="docs/screenshots/setup.png" width="48%" alt="The setup dashboard">
  <img src="docs/screenshots/connect.png" width="22%" alt="The app connecting to your server over its built-in private network">
  <img src="docs/screenshots/home.png" width="22%" alt="The home screen">
</p>

**2. Open the setup page** the installer prints (`http://<server>:8080/setup`) and follow it:

1. Name your assistant. It answers to that name.
2. Pick its brain and paste the key. "Save and test" checks it works.
3. Optional: a voice key, for talking out loud.
4. Connect the server to Tailscale with one tap. Then, in the Tailscale admin console's DNS page, turn on
   **MagicDNS** and **HTTPS Certificates** (once per Tailscale account).
5. Choose who can use it: everyone on your Tailscale network, or only the people you list.

**3. Install the app** from [Releases](https://github.com/Neoo-Blue/lumen/releases/latest). Open it and sign in to the
**same Tailscale account**. It finds your server by itself. Then let it become your phone's assistant and grant the
permissions it asks for.

After setup the server only answers on your private network. The full guide (updating, backups, removing, Headscale)
is in [deploy/README.md](deploy/README.md).

---

## What it can do

### Calling it up
- **Default assistant**: long-press the side or power button, swipe up from a bottom corner, or the assistant gesture.
  A panel opens over whatever app you are in, so you never lose your place.
- **"Hey Lumen" wake word**: works offline, on the phone (Vosk). It steps aside when another app uses the microphone,
  during calls, and when the screen is off (optionally kept on while driving or charging).
- **Other ways in**: a Quick Settings tile, a home-screen orb widget, app-icon shortcuts, "Ask Lumen" when you select
  text, the share sheet, and over the lock screen (unlock to continue).
- **Voice or typing**: live captions while you speak, spoken replies, many voices, about two dozen languages. A
  conversation ends after a few seconds of silence; the mic never stays open.

### Doing things on the phone
- **Apps**: open any app, or a specific screen inside one (a chat, a tab, a scanner), install apps through the store.
- **Settings**: Wi-Fi, Bluetooth, mobile data, flashlight, Do Not Disturb, rotation, dark mode and any other Quick
  Settings switch; volume, brightness, silent and vibrate, screen timeout.
- **Phone and messages**: call, end a call, read the call log, read texts, write texts (sending waits for your OK),
  contacts (read and save).
- **Time**: alarms, timers, calendar (read your day, add events).
- **Media**: play, pause, skip; play music and videos stored on the phone; radio and podcasts.
- **Camera and photos**: take a photo, pick or find photos, "look" mode where it sees through the camera live.
- **Phone status**: battery, network, location (a fresh fix), paired Bluetooth devices, step count.
- **Instant commands**: plain requests like "flashlight on", "open WhatsApp", "volume up" run on the phone in
  milliseconds with no AI call at all, even offline.

### Working any app's screen
- **Screen control** through Android's accessibility service: it reads the app in front of you, taps, types, scrolls
  (it finds things below the fold), goes back or home, and does several steps in one go.
- **Sees the screen**: takes a screenshot to understand charts, images and pages (password boxes are painted over).
- **Your OK for anything that matters**: a tap on Send, Pay, Buy, Order, Book, Delete, Post, Subscribe, Install,
  Allow and similar always waits for your confirmation on screen. Installer and permission screens always ask.
- **Forms with a review step**: before filling a booking or sign-up form, it shows every box on an overlay over the
  page, filled with what it knows. You check or change each one, then press Confirm and watch it fill them in.
- **Long tasks, your choice**: run in the background through its own hidden browser, or take over the screen while
  you watch. A slim progress card stays at the top and touches pass through to the app.

### Websites without an API
- **Built-in browser**: sign in to any site once inside Lumen. Afterwards it can read that site with your session
  in the background, and act on it with your confirmation.

### Remembering and searching
- **Memory per person**: who you are, how you like things, facts you told it, and a searchable history. Ask it what it
  knows and delete anything. Separate profiles for personal and work.
- **Search your life**: across your texts, notifications and photos on the phone, and your connected services.

### Work that keeps going

<img src="docs/screenshots/routines.png" width="30%" align="right" alt="Scheduled jobs and notification triggers">
- **Jobs**: "every weekday at 7:05, brief me on my day", one-off reminders, routines. Results arrive as notifications
  with a receipt of what it did.
- **Triggers**: "when my pharmacy texts that it's ready, add it to my list". Started by a notification from an app you
  name, matched on the phone.
- **Learned skills**: the first time it does a task in an app ("open my liked songs on Spotify"), it keeps the steps on your phone. The next time you say it, the phone replays them by itself with no AI call, in a few seconds. Steps that type your words or send anything are never replayed on their own.
  Next time it replays them natively, about a second per step, with no AI call. You can view and edit them.
- **Receipts**: every action taken on your behalf is written down: what, when, which apps, what was held for your OK,
  and anything it refused.

### Connected services
- **Any MCP server** (with sign-in where the server supports it), and **any HTTP API** you give a key for. Keys are
  encrypted on your server and only ever sent to that service's own address.
- **Built in**: web search*, weather, places and routes (starting from where you are), nearby places, people,
  stocks and currencies, live flight prices, rides (Uber and Lyft links), notes, reminders, checklists, trips.

\* Live web search currently needs an Anthropic model (see below).

### Calls to businesses (optional)
- It can phone a restaurant, office or shop for you: book, ask, or wait on hold. The call opens by saying it is an AI
  assistant calling for you and that the call is recorded. It never pays or agrees to costs. You get a summary and the
  transcript. Needs extra setup (see [deploy/README.md](deploy/README.md#phone-calls-to-businesses-optional-advanced)).

### Voice
Choose who listens and speaks in the dashboard. Live captions stream while you talk wherever the provider allows it.

| Provider | Listening (speech to text) | Speaking | Tested with a real key |
|---|---|---|---|
| Cartesia | Live, streaming | Yes | Yes |
| Deepgram | Live, streaming | Yes | Yes |
| ElevenLabs | Live, streaming | Yes | Not yet |
| OpenRouter | After you stop talking (no live captions) | Yes | Not yet |
| LiveKit Inference | Live, streaming (any model LiveKit offers) | Yes (WAV audio) | Not yet |

Your server holds the voice key and talks to the provider itself; the phone never sees the key or the provider's
address. Details per provider: [server/VOICE.md](server/VOICE.md).

### On the road
- **Driving mode** turns on when your phone connects to the car's Bluetooth: short spoken answers, and spoken yes or
  no for confirmations.

### Your choice of brain
| Provider | Status |
|---|---|
| Anthropic (Claude) | Full support. What Lumen is built and tuned on. Includes live web search. |
| OpenAI | Supported through the OpenAI-compatible interface. No live web search yet. |
| Google Gemini | Supported through Google's OpenAI-compatible endpoint. No live web search yet. |
| Ollama, vLLM, LM Studio (local) | Supported. Quality depends heavily on the model: phone control needs strong tool use and, for screenshots, vision. |
| Any other OpenAI-compatible service | Supported (Groq, Together, OpenRouter and so on). |

**Or use a plan you already pay for.** Lumen can drive the official command-line tools you are signed in to, on
your server, instead of an API key. Install and sign in from the dashboard; one turn takes about 3 to 4 seconds.

| Tool | Plan it uses | Status |
|---|---|---|
| Claude Code | Claude Pro or Max | Tested: tool calls and pictures. Signs in with a long-lived token. |
| Codex | ChatGPT Plus, Pro or Business | Installs and signs in from the dashboard (device code). Pictures supported. |
| Gemini CLI | Google AI Pro, Ultra or free | Supported; sign in with Google, then paste the code back. |
| OpenCode | Any provider OpenCode signs in to | Supported. |
| Antigravity | Google Antigravity | Works if its command-line tool is installed on the server (it is not on npm). |

These run one turn at a time through each company's own app, with that app's own tools switched off; Lumen keeps
control of what happens on your phone. Personal use only: check your plan's terms before relying on it.

### Households
- Everyone on your Tailscale network can use the same server, each with their own private memory, identified by
  their Tailscale login. No accounts or passwords. The owner decides who is allowed.

---

## What it can't do (yet)

Being honest about limits:

- **iPhone**: Android only. iOS does not let an app replace Siri or control other apps.
- **Android version**: Android 11 or newer, 64-bit ARM phones only (nearly every phone from the last several years).
- **Tested hardware**: one Samsung foldable so far. Other phones should work but screen control details vary by
  manufacturer. Reports welcome.
- **Play Store**: not on Google Play. Google's policies do not allow this level of phone control in Play apps, so you
  install the APK yourself. Android will ask you to allow installs from your browser once, and may show a Play Protect
  prompt.
- **Developer verification**: Google plans to require verified developers for sideloaded apps in more countries
  during 2027. How this affects Lumen is not known yet.
- **Accessibility permission resets**: after an app update or force-stop, Android may switch screen control off.
  Lumen notices and shows a one-tap way to turn it back on.
- **Live web search** works only with an Anthropic model for now.
- **Voice** needs a key from one of the voice providers above. Typing works without one. There is no fully local
  speech option yet. ElevenLabs, OpenRouter and LiveKit voice are tested against their documented protocols but not
  yet with live keys.
- **Subscription brains** run each request through a command-line tool, so they are slower than an API key (a few
  seconds per step) and depend on that company keeping its tool's behavior and terms the same. Live web search is
  not available through them yet.
- **Wake word** is English only ("Hey Lumen" or your assistant's name in a later release; see below).
- **Phone calls to businesses** need a Cartesia account with a phone number, and exposing one webhook path to the
  internet with Tailscale Funnel. Off by default.
- **Headscale** works as a coordination server, but HTTPS certificates depend on your Headscale version. Tailscale's
  free plan is the tested path.
- **Some websites resist automation**: heavy booking sites can collapse forms or block assistive input. Lumen stops
  and tells you rather than guessing. It never completes a purchase without your confirmation.
- **No banking or payment apps automation by design**: it will not enter card numbers or passwords on your behalf
  (it can open the page and let you do it).
- **Offline**: only instant commands (flashlight, open an app, volume and the like) work without your server.
- **Multiple servers**: one phone talks to one server at a time.
- **Model cost**: you pay your AI provider for usage with your own key. A busy day of phone control can be many
  requests; local models cost nothing per request.

---

## What's coming

Planned, roughly in order. Nothing here is promised by a date.

**Next**
- Wake word in your assistant's own name, and more wake word languages.
- Live web search for every model (a built-in search connector).
- Local speech: on-server speech recognition and voices, so voice works with no external service.
- A Docker image on the GitHub Container Registry, so updating is one pull.
- Testing and fixes across more phones (Pixel, other Samsung models, Xiaomi, OnePlus).

**Later**
- Skill library: share the skills your phone learned and add other people's, like a shortcuts gallery. Each skill shows its steps before you add it, and sending, paying or posting still asks you first.
- Teach mode: show it a task once by doing it yourself while it watches, and it saves the skill.
- Purchase guardian: before any payment, a clear summary of exactly what you are buying and the total.
- Live progress in the status bar for long tasks.
- Call screening: answer unknown callers for you and tell you who it is and what they want.
- A unified inbox: texts, mail and messaging apps in one place, with drafted replies.
- Desktop continuity: start something on your computer and finish it on your phone, and the other way around.
- Smart home: Home Assistant as a built-in connector.
- Smaller and offline: a small on-phone model for simple requests when the server is unreachable.

Ideas and pull requests welcome; open an issue to discuss bigger changes first.

---

## Privacy and safety

<img src="docs/screenshots/guard.png" width="30%" align="right" alt="A text message that tried to give Lumen orders, shown and refused">

- **Your data lives on your server**: memory, history, settings, and the keys for services you connect (encrypted
  with a key made on your server at first start).
- **The AI provider you pick** sees the requests it answers, with only what each answer needs. Choose a local model if
  nothing should leave your home.
- **The private connection** is Tailscale, encrypted end to end between your phone and your server. Lumen turns off
  Tailscale's log upload on both ends. After setup the server refuses everything that does not come through it.
- **No telemetry to anyone else**: the app's diagnostics go only to your server.
- **Guarded actions**: sending, paying, buying, booking, deleting, posting and installing wait for your OK. A request
  arriving from outside cannot skip that.
- **Prompt injection defenses**: text written by others (messages, notifications, web pages, the screen) is treated as
  data, never as instructions. If something tries to give Lumen orders, it is withheld from the model and you are
  shown exactly what was refused and where it came from. After reading such text in a request, Lumen will not set up
  jobs or send private data elsewhere.
- **Pictures**: screenshots paint over password fields before anything is sent.

---

## How it fits together

```
 Phone                                   Your server (Docker or LXC)
 ┌──────────────────────────────┐        ┌────────────────────────────────────────┐
 │ Lumen app                    │        │ Tailscale  ── tailscale serve (HTTPS) ─┐│
 │  · page (the interface)      │        │                                        ▼│
 │  · native: screen control,   │ ─────► │ Lumen server: agent, memory, jobs,     ││
 │    phone actions, wake word  │ private│ connectors, setup dashboard            ││
 │  · Tailscale, built in       │ network│                       │                 │
 └──────────────────────────────┘        └───────────────────────┼─────────────────┘
                                                                 ▼
                                                   the AI model you chose
```

| Folder | What it is |
|---|---|
| `server/` | Node.js 22 server. No build step. `brain.mjs` talks to the model, `server.mjs` runs the agent, `setup.html` is the dashboard. |
| `android/` | The Android app (Kotlin): a WebView interface plus native phone control. |
| `android/tsbridge/` | Tailscale embedded in the app (Go, built with gomobile into an Android library). |
| `deploy/` | Docker Compose, the Proxmox and Debian installers, and the server guide. |

**Building it yourself:** the server needs only Node.js 22 (`cd server && npm install && node server.mjs`). The app
needs Go, the Android SDK and NDK, and JDK 17: `cd android/tsbridge && bash build.sh`, then `cd android && bash ci-build.sh`.
Put your own signing key at `android/signing/lumen.keystore` so updates install over each other.

---

## Requirements

| Part | Needs |
|---|---|
| Phone | Android 11+, 64-bit ARM, about 150 MB free (plus 40 MB for the offline wake word) |
| Server | Any 64-bit Linux machine that stays on: 1 CPU core and 1 GB of memory is enough; 2 GB recommended |
| Network | A Tailscale account (free for personal use) or your own Headscale |
| AI | An API key for Anthropic, OpenAI or Gemini, or a local model server |
| Voice (optional) | A key from Cartesia, Deepgram, ElevenLabs, OpenRouter or LiveKit |

---

## FAQ

**Why not just use Gemini or Siri?** Lumen works on your terms: your server, your memory, your choice of model, and it
can operate any app on the phone, not only the ones that integrate with an assistant.

**Does it need my phone to be rooted?** No. It uses Android's standard assistant and accessibility features.

**Can it do something without my knowing?** Anything that sends, buys, pays, books, deletes, posts or installs waits
for you. Everything it does is listed in its receipts.

**Can I rename it?** Yes, in the setup dashboard. It answers to the new name.

**Is it open source?** The source is open to read, change and run for personal and other noncommercial use. Because
commercial use is not permitted, it is "source-available" rather than open source in the OSI sense.

---

## License

Free for personal and other noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE.md).
Commercial use needs written permission from the author. Bundled third-party components keep their own licenses:
Tailscale (BSD-3-Clause), gVisor (Apache-2.0), WireGuard (MIT), Vosk (Apache-2.0); see [NOTICE](android/tsbridge/NOTICE).
Tailscale and WireGuard are trademarks of their owners; Lumen is not affiliated with them.
