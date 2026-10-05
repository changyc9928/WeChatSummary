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

```bash
export GEMINI_API_KEY=...   # and/or NVIDIA_API_KEY
docker compose up --build
```

Frontend: https://localhost (or :3001). Backend: http://localhost:8080.

See `exporter/README.md` for running the `bridge.exe` key-recovery sidecar on
Windows, and `frontend/README.md` for frontend dev details.
