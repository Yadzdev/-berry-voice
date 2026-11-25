# BerryVoice Security Notes

- `api.secret` is a private shared credential for Velocity HTTP pairing creation and authenticated Paper backend WebSockets. Keep it only in private Velocity/Paper configuration files.
- Never place `api.secret`, session tokens, pairing codes, or Floodgate private keys in the root website, `config.js`, GitHub Pages, screenshots, or public issue logs.
- Use HTTPS/WSS for the public Bedrock portal and firewall the raw API/WebSocket ports. Only the reverse proxy should publish `/ws`, `/api/*`, and `/health`; never publish `/backend` or `/internal/pair/create`.
- Configure `X-Real-IP` on the reverse proxy; the HTTP pair-consume endpoint uses a per-IP rate limit.
- Pair codes contain eight unambiguous uppercase letters/digits, expire and can only be consumed once. Tokens are random, time limited and held in memory.
- The website asks for microphone permission. Opus audio frames are relayed in memory; this project contains no audio-recording feature.
- Bedrock browser transport uses WSS. Java's normal SVC transport remains the official SVC UDP protocol.
- Use trusted, up-to-date Simple Voice Chat builds and keep the Velocity proxy plugin and Paper server plugin compatible with one another.
- If a shared secret is exposed, generate a new one on Velocity and update every Paper backend before reconnecting the network.
