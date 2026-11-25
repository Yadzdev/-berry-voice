# Simple Voice Chat — Velocity + Paper setup for BerryMC

This is required for Java players to use voice in Minecraft. BerryVoice does not replace Simple Voice Chat's UDP transport.

## Install components

- Simple Voice Chat Bukkit/Paper plugin on every backend Paper.
- Simple Voice Chat Velocity proxy plugin on Velocity.
- Simple Voice Chat client mod on every Java player's matching Minecraft loader/version.
- BerryVoice Velocity and Paper plugins from this project.

Download the official Simple Voice Chat builds from the project's Modrinth page: https://modrinth.com/plugin/simple-voice-chat

## Network layout

- Java SVC audio: UDP from the Java client's game to the SVC Velocity proxy plugin.
- Bedrock browser audio: HTTPS/WSS to `relay.berrymc.net.br`, then the BerryVoice Paper adapter injects/extracts Opus via the SVC API.
- Website: `voice.berrymc.net.br` (GitHub Pages).

These are separate traffic types and hostnames. The HTTPS relay does not replace the SVC UDP port.

## Proxy UDP port

After the official Velocity plugin starts, it generates `plugins/voicechat/voicechat-proxy.properties`. Set the `port` to a UDP allocation available to Velocity. The SVC default `-1` means use the same port number as the Velocity Minecraft port; make sure your hosting provider actually exposes that port over UDP. In Pterodactyl, reserve the UDP allocation if your host requires an explicit allocation.

Example if using a separate allocation:

```properties
port=YOUR_UDP_ALLOCATION
bind_address=*
voice_host=voicechat.berrymc.net.br:YOUR_UDP_ALLOCATION
```

If the default `port=-1` is valid in your hosting environment, you may keep it. Do not copy the example port literally; use the actual port allocated to your Velocity server. Create an A record `voicechat` pointing to the public IP used by the SVC proxy if you set `voice_host` to that hostname.

## Paper backend voice ports

Install the Simple Voice Chat Paper plugin on every backend. The official proxy plugin detects backend voice ports and forwards voice packets. If multiple Paper servers run on the same physical host/interface, assign each backend a different local UDP port in its `plugins/voicechat/voicechat-server.properties`, according to the official proxy guide. Avoid binding multiple backend SVC instances to the same UDP port.

## Testing Java voice

1. Join with a Java client containing the matching Simple Voice Chat mod.
2. Press `V` and confirm the voice chat shows connected.
3. Test the microphone and confirm the player can hear another Java player in proximity.
4. Then test Bedrock: join with Floodgate, use `/voz`, open the web portal, enter the code and allow microphone access.
5. Test both directions: Java → Bedrock and Bedrock → Java.

Official proxy instructions: https://modrepo.de/minecraft/voicechat/wiki/proxy_setup
