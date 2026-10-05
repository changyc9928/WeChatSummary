# WeChatSummary

A web application that exports WeChat 4.x chat data, media, and voice messages,
then uses AI to transcribe voice messages and summarize chats.

> **Note:** This project is mostly powered by AI (built with AI assistance).

## Architecture

| Component | Stack | Description |
|-----------|-------|-------------|
| `frontend/` | React + Vite (nginx) | Web UI; HTTPS on ports 443/3001 |
| `backend/` | Java Spring Boot | REST API, chat processing, AI (Gemini/NVIDIA), Whisper transcription |
| `exporter/` | Go | `bridge` sidecar that drives key recovery, DB decryption, and chat export on the Windows host running WeChat |
| `raw-extract/` | — | Extracted WeChat data (WCDB/SQLCipher DBs, media) |

Infrastructure via `compose.yaml`: PostgreSQL, Redis, RabbitMQ, and a
faster-whisper transcription service.

## Quick start

You can put API keys in the root `.env` (loaded automatically by compose), or leave them blank and configure provider/model API keys later in the web UI's Settings page (stored in the database, takes precedence). `.env` also holds `VITE_API_BASE_URL`.

```bash
docker compose up --build
```

Frontend: https://localhost (or :3001). Backend: http://localhost:8080.

### Chat export sidecar (bridge)

The browser cannot read WeChat's process memory or decrypt its SQLCipher
databases, so key recovery and chat export run on the Windows machine where
WeChat is logged in, via the `bridge` sidecar:

```powershell
.\bridge.exe --port 8787 --token <optional-secret>
```

You don't need to build it yourself: the web UI (Dashboard → bridge panel)
lets you download the prebuilt `bridge.exe` served by the backend
(`GET /api/tools/bridge`). To rebuild from source instead, cross-compile
with `GOOS=windows GOARCH=amd64 go build -o bridge.exe ./cmd/bridge` in
`exporter/` (or use the copy in `tools/`).

Then, with WeChat running and logged in, open the web UI and use the
"bridge" features to: Get DB key (verify against the real chat DB), Get media
keys, and Export chat + media ZIP. The frontend talks to it at
`http://127.0.0.1:8787`; pass the same `--token` in the UI if you set one.
See `exporter/README.md` for flags (`--allow-origins`, `--log-level`, ...) and
security details.

## Rough performance expectations

Highly dependent on hardware, API keys, and chat size — ballpark figures:

| Step | Typical time |
|------|--------------|
| Bridge key recovery (Windows, WeChat running) | seconds to ~1 min |
| Chat + media export ZIP (GB-scale media) | minutes to tens of minutes |
| Voice transcription (faster-whisper, CPU, `large-v3`) | ~real-time to several× real-time of audio length |
| AI summary / image / video description (cloud LLM) | seconds to a minute per batch |
| First docker compose up (image pulls, whisper model download) | 10–30 min |

Tip: reduce `WHISPER_MODEL` (e.g. `small` or `base`) for much faster
transcription at lower accuracy; CPU transcription of large chats can take a
long time. Subsequent runs reuse the `whisper_models` and `pgdata` volumes.

See `exporter/README.md` and `frontend/README.md` for more detail.
