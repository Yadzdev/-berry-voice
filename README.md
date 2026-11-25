# BerryVoice Network 2.1.1 — BerryMC

Bridge Bedrock browser voice into **Simple Voice Chat**. Java players keep using the Simple Voice Chat mod inside Minecraft; they do not open the Berry Voice website. Bedrock players use Geyser/Floodgate plus the Berry Voice portal.

## Architecture

- **Java clients:** official Simple Voice Chat client mod, microphone and audio inside Minecraft.
- **Paper backends:** official Simple Voice Chat Bukkit/Paper plugin + BerryVoice Paper adapter. `PlayerAudioListener` forwards to the Bedrock browser the sound packets that Bedrock should receive; `AudioSender` injects browser Opus frames as the Bedrock player's microphone.
- **Velocity:** official Simple Voice Chat Velocity proxy plugin routes SVC UDP traffic between Paper backends and Java clients; BerryVoice Velocity handles pairing, private backend tunnels and the Bedrock browser WebSocket.
- **Bedrock clients:** Geyser + Floodgate, `/voz`, then `https://voice.berrymc.net.br` with the temporary 8-character code.

The bridge uses the official Simple Voice Chat API: `VoicechatServerApi.createAudioSender/registerAudioSender`, `playerAudioListenerBuilder/registerAudioListener`, `VoicechatConnection.setConnected(true)` for players without the client mod, and `MicrophonePacketEvent` to enforce server-side mute for Java players. See the official API references linked in this README.

## What is included

- `velocity-plugin/`: Velocity plugin — pairing API, one-time 8-character codes, backend authentication, WebSocket relay, reconnection and session routing.
- `paper-plugin/`: Paper plugin — `/voz`, Java inventory menu, Floodgate forms, join messages, configurable text/menu files, groups and mute controls, SVC audio adapter.
- Root `index.html`, `app.js`, `config.js`, `styles.css`, `assets/`, `CNAME`: GitHub Pages web client for Bedrock.
- `deploy/`: Caddy and Nginx reverse-proxy examples for HTTPS/WSS.
- `.github/workflows/build.yml`: GitHub Actions Maven build that publishes the two JARs as an Actions artifact.

## Requirements

- Java 21 or newer on Velocity and every Paper backend.
- Velocity 3.4.x and Paper 1.21.x (the Paper module is set to Paper API 1.21.11).
- **Simple Voice Chat 2.6.25 or newer:** Bukkit/Paper plugin on every Paper backend, Velocity proxy plugin on Velocity, and the compatible client mod on every Java player's client.
- Geyser and Floodgate on your network for Bedrock detection/forms. Floodgate must be able to identify Bedrock UUIDs on the Paper backends.
- A public UDP port for Simple Voice Chat on Velocity; HTTPS/WSS TCP 443 for the Bedrock web portal/relay.
- A reverse proxy (Caddy or Nginx) and a hostname for the BerryVoice relay.

Official Simple Voice Chat references:

- API overview: https://voicechat.modrepo.de/
- `VoicechatServerApi`: https://voicechat.modrepo.de/de/maxhenkel/voicechat/api/VoicechatServerApi.html
- `PlayerAudioListener.Builder`: https://voicechat.modrepo.de/de/maxhenkel/voicechat/api/audiolistener/PlayerAudioListener.Builder.html
- `AudioSender`: https://voicechat.modrepo.de/de/maxhenkel/voicechat/api/audiosender/AudioSender.html
- `VoicechatConnection`: https://voicechat.modrepo.de/de/maxhenkel/voicechat/api/VoicechatConnection.html
- Velocity proxy setup: https://modrepo.de/minecraft/voicechat/wiki/proxy_setup

## Important compatibility note

This is a **bridge to Simple Voice Chat**, not a replacement for its UDP transport. Do not install the old `BedrockSimpleVoice` plugin alongside this bridge: both may attempt to register an audio sender/listener for the same Bedrock player. Use the official Simple Voice Chat Paper plugin + Velocity proxy plugin, then install BerryVoice.

Java players must have the compatible Simple Voice Chat mod. The website is for Bedrock browser audio only. A Java player without the client mod cannot use the normal in-game SVC microphone.

## Build

From the project root:

```bash
mvn --batch-mode clean package
```

On Windows PowerShell:

```powershell
.\build.ps1
```

JAR output paths:

```text
velocity-plugin/target/berryvoice-velocity-2.1.1.jar
paper-plugin/target/berryvoice-paper-2.1.1.jar
```

This source package does not contain precompiled JARs. The authoring environment used for this package had JDK 21 but no Maven executable and could not reach dependency repositories, so a Maven compile and live Minecraft audio test were **not** performed here. Push this project to GitHub and open **Actions → Build BerryVoice Network** to run the reproducible Java 21/Maven build. Treat the JARs as unverified until that workflow succeeds and the integration is tested on your actual Velocity/Paper network.

## Install Simple Voice Chat first

1. Install the **Simple Voice Chat Bukkit/Paper plugin** on every Paper backend.
2. Install the **Simple Voice Chat Velocity proxy plugin** on Velocity.
3. Java players install the matching Simple Voice Chat client mod for their Minecraft version/loader.
4. Configure a public UDP port for the SVC Velocity proxy. The official proxy plugin routes voice packets to the correct backend. Its `voicechat-proxy.properties` is normally generated in `plugins/voicechat/` on Velocity.
5. In `voicechat-proxy.properties`, set `port` to the UDP allocation opened for the Velocity proxy. `-1` means use the same port number as the proxy's game port; verify that this allocation accepts UDP. Optionally set `voice_host` to a separate public hostname such as `voicechat.berrymc.net.br` (with the configured port if needed).
6. Each Paper backend also has `plugins/voicechat/voicechat-server.properties`. When multiple Paper servers share a machine, assign a unique internal voice port to each backend as required by the official proxy guide. Do not reuse one backend port on multiple processes bound to the same interface.

This SVC UDP hostname is independent of the Berry Voice web/relay hostnames. Do not point the SVC UDP `voice_host` at the GitHub Pages site.

## Install BerryVoice on Velocity

1. Copy `berryvoice-velocity-2.1.1.jar` into the Velocity `plugins/` folder.
2. Start Velocity once to generate `plugins/berryvoice/config.properties`.
3. Stop Velocity and set `api.secret` to a randomly generated value of at least 32 characters. Example on PowerShell:

```powershell
([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
```

4. Set the API and WebSocket bind/ports to the allocations available to Velocity. Default values are HTTP `9876` and private WebSocket `8766`.
5. Start Velocity. Check the console for `BerryVoice 2.1.1 iniciado` and `BerryVoice WebSocket ativo`.

Example `plugins/berryvoice/config.properties`:

```properties
api.bind=0.0.0.0
api.port=9876
ws.bind=0.0.0.0
ws.port=8766
api.secret=REPLACE_WITH_A_RANDOM_SECRET_OF_32_PLUS_CHARACTERS
cors.origin=https://voice.berrymc.net.br
pair.ttl-seconds=180
session.ttl-hours=12
ws.max-message-chars=32768
```

Never publish `api.secret`. Use this same value in every Paper backend's private `config.yml`. Do not put it in `config.js`, HTML, JavaScript, or GitHub Pages.

## Install BerryVoice on every Paper backend

1. Copy `berryvoice-paper-2.1.1.jar` to `plugins/` on every Paper backend.
2. Keep the official Simple Voice Chat Paper plugin installed. BerryVoice declares it as a hard dependency.
3. Install Floodgate on the Paper backend and configure Floodgate forwarding from Velocity according to Geyser/Floodgate's official setup. The Paper API dependency is provided at compile time; it is not shaded into BerryVoice.
4. Start the backend once to generate `plugins/BerryVoice/config.yml`, `messages.yml`, and `menus.yml`.
5. Set `server-id` to exactly the backend key/name configured in `velocity.toml` (for example `survival`, `lobby`, or `events`). Use a different ID for each backend.
6. Set `api.secret` to the same secret as Velocity. Set `api.base-url` and `relay.websocket-url` to the **private/reachable Velocity IP and port allocations**. The `127.0.0.1` defaults only work when both processes share the same network namespace.
7. Restart Paper and check the console for `Simple Voice Chat API registrada` and `Conexão segura com o relay Velocity estabelecida`.

Example `plugins/BerryVoice/config.yml`:

```yaml
site-url: "https://voice.berrymc.net.br"
server-id: "survival" # must match the key in velocity.toml
api:
  base-url: "http://VELOCITY_PRIVATE_IP:9876"
  secret: "THE_SAME_PRIVATE_SECRET_AS_VELOCITY"
relay:
  websocket-url: "ws://VELOCITY_PRIVATE_IP:8766/backend"
welcome:
  enabled: true
```

## Paper configuration files

Edit these files directly on each backend — no recompilation is required for text/menu changes:

- `plugins/BerryVoice/config.yml`: site URL, backend ID, private API endpoint and secret, private backend WebSocket endpoint.
- `plugins/BerryVoice/messages.yml`: welcome messages, pair code, expiry, mute/group feedback and errors. Supports Bukkit `&` color codes.
- `plugins/BerryVoice/menus.yml`: Java inventory title/size/slots/material/name/lore/actions and Bedrock form titles/buttons.

Use `/voz reload` (permission `berryvoice.admin`) after changing `messages.yml` or `menus.yml`. Configuration changes under Velocity's `config.properties` require a proxy restart.

## Commands

| Command | Function |
|---|---|
| `/voz` | Opens the Java inventory menu or Bedrock Floodgate form. |
| `/voz conectar` | Bedrock only: creates an 8-character, one-use web pairing code. Java is instructed to use the SVC mod in-game. |
| `/voz mute` | Mutes the player microphone on the server for both Java and Bedrock. |
| `/voz desmutar` | Unmutes the player microphone for both Java and Bedrock. |
| `/voz alternar` | Toggles microphone mute for both Java and Bedrock. |
| `/voz grupos` | Lists public, non-password SVC groups on this backend. |
| `/voz criar <nome>` | Creates a persistent isolated SVC group on this backend and joins it. |
| `/voz entrar <id>` | Joins a listed group by UUID. |
| `/voz sair` | Leaves the group and returns to proximity. |
| `/voz reload` | Reloads messages and menus (admin). |

The SVC group list is local to the Paper backend where the group was created. Cross-backend group persistence is not implemented by this bridge; use the SVC proxy for normal Java voice routing, and keep Bedrock browser groups on the same backend as the Bedrock player.

## Domains and DNS

Keep these hostnames separate:

| Hostname | Purpose | DNS |
|---|---|---|
| `voice.berrymc.net.br` | Static Bedrock web client on GitHub Pages | CNAME to `yadzdev.github.io` |
| `relay.berrymc.net.br` | HTTPS API + WSS relay served by Velocity/reverse proxy | A record to the public IP of the reverse proxy/Velocity host |
| `voicechat.berrymc.net.br` | Optional Simple Voice Chat UDP host for Java clients | A record to the public IP of the Velocity SVC UDP host |

The root `CNAME` file in this project is `voice.berrymc.net.br`. GitHub Pages should deploy from branch `main`, folder `/ (root)`, using a public repository (or a plan that supports private-repository Pages).

## Reverse proxy and firewall

- Caddy example: `deploy/Caddyfile`.
- Nginx example: `deploy/nginx.conf.example`.
- Expose public TCP 443 for `relay.berrymc.net.br` and allow the reverse proxy to reach Velocity TCP ports `9876` and `8766`.
- Do **not** publish `/backend` or `/internal/pair/create` through the reverse proxy. The examples expose only `/ws`, `/api/*`, and `/health`.
- Firewall the raw API/WebSocket ports so only the reverse proxy and trusted Paper backends can reach them. The API uses `X-BerryVoice-Key`; the Paper backend WebSocket uses the same private secret.
- Configure `X-Real-IP` in the reverse proxy. The public code-consume endpoint is rate-limited per client IP.
- Open the SVC proxy's UDP port separately; HTTPS/WSS TCP 443 does not carry Simple Voice Chat's in-game UDP traffic.

If Velocity is in Pterodactyl, use its actual allocated IP/port for the Paper private endpoints and the Caddy upstream. Do not assume `127.0.0.1` works across separate containers. Reserve the SVC UDP allocation and set the same port in `voicechat-proxy.properties`.

## Player flow

### Java

1. Installs the compatible Simple Voice Chat mod.
2. Joins BerryMC and presses `V` (or the configured SVC key).
3. Speaks/listens inside Minecraft. No Berry Voice website or pairing code is required.

### Bedrock

1. Joins through Geyser/Floodgate.
2. Uses `/voz` and chooses **Gerar código**.
3. Opens `https://voice.berrymc.net.br`, enters the 8-character code and allows the browser microphone.
4. The portal uses Opus/WebCodecs over WSS; the Paper adapter passes audio into/out of the official Simple Voice Chat API.

Use a modern Chromium browser with WebCodecs Opus support for the Bedrock web microphone/audio. Safari and older browsers may not expose the required WebCodecs APIs.

## What is and is not guaranteed

- The bridge uses SVC's server-side routing and the listener is defined as receiving the sound packets a target player would theoretically receive; this preserves SVC's server-side recipient/proximity/group filtering for Bedrock downlink.
- Java sends and receives with the native SVC mod inside Minecraft. Browser audio is only needed for Bedrock.
- Bedrock browser playback is mono/non-spatial in the web client: it does not reproduce SVC's full client-side 3D pan/attenuation UI. It receives the packets selected for the Bedrock player by the server API.
- Pair codes, session tokens, group selection and mute state are currently in Velocity memory. A Velocity restart invalidates active browser sessions and pending codes. Persistent groups are owned by SVC on each Paper backend.
- Audio is not recorded by BerryVoice; Opus frames are forwarded in memory.
- The JAR build and a real multiplayer audio test must succeed before production use. No one can honestly promise zero errors without running that build against the exact Paper, Velocity, Simple Voice Chat, Geyser and Floodgate versions in the target hosting environment.
