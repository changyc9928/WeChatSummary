# WeChatSummary exporter

Go tooling for WeChat 4.x WCDB/SQLCipher databases and the **local key
bridge** that lets the web frontend drive native key recovery, media
decryption, and chat export on the Windows machine running WeChat.

```
exporter/
├── cmd/bridge      local HTTP sidecar for the browser frontend (Option 1)
└── internal/
    ├── bridge      bridge HTTP API (CORS, auth, validation, DB discovery)
    ├── scan        Windows process-memory scanner (pattern-driven)
    ├── sqlcipher   SQLCipher 4 page decryption (raw key / passphrase modes)
    ├── sqlite      minimal read-only SQLite format reader
    ├── util        key parsing (hex / x'...' / JSON key cache files)
    └── logx        tiny leveled logger
```

## The browser cannot scan memory — the bridge can

Chrome/Firefox sandbox web pages: there is no web API that reads another OS
process's address space (`ReadProcessMemory` has no JS equivalent), by design.
The frontend therefore delegates native work (key recovery from the running
WeChat, `.dat` media decryption, chat export) to this sidecar:

```
┌─────────────┐   fetch (CORS + optional token)   ┌──────────────────────┐
│  browser UI │ ─────────────────────────────────► │  bridge (Go binary)  │
└─────────────┘                                    │  binds 127.0.0.1 only │
                                                   │  drives wechat_key_   │
                                                   │  tool.dll in WeChat   │
                                                   └──────────────────────┘
```

Flow: 1) user runs `bridge.exe` on their Windows machine (WeChat running);
2) the frontend clicks "Get DB key" (DLL-driven) / "Get media keys" /
"Export chat + media ZIP"; 3) every key the bridge recovers is *verified
cryptographically* against the real chat database (HMAC-SHA512 check in
`internal/sqlcipher` / page-1 magic probe, media keys against real `_t.dat`
templates), so a wrong or stale result can never silently corrupt an export.

## Build

```bash
cd exporter

# macOS / Linux (works, but memory scan returns 501 - unsupported_platform)
go build ./cmd/bridge

# Windows (the platform with a working scan engine)
GOOS=windows GOARCH=amd64 go build -o bridge.exe ./cmd/bridge
```

Tests (no external DB needed — they synthesize SQLCipher page-1 files and
verify raw-key, passphrase, CORS, auth and DB discovery end to end):

```bash
go test ./...
```

## Run

```bash
bridge.exe \
  --port 8787 \
  --token <optional-secret> \
  --allow-origins http://localhost:5173,http://127.0.0.1:5173,http://localhost:3001,http://127.0.0.1:3001 \
  --log-level info
```

(The defaults already cover `:5173`/`:3001` on localhost — this shows how to
extend them; `--cors-any` disables the origin check entirely.)

`--log-level info` (default) stores INFO/WARN/ERROR in the bridge log ring
(the frontend "Bridge activity log" panel). Pass `--log-level debug` to add
DEBUG lines from the memory scans (window probes, per-chunk progress, candidate
counts): `bridge.exe --log-level debug`.

The server refuses to bind any non-loopback address, CORS is allowlisted, and
`--token` (optional) adds `Authorization: Bearer <token>` on every request.
Log a token anywhere the frontend reads it is the only secret involved —
the DB path and keys are just request payloads.

For WeChat desktop, run the bridge **on the same machine as WeChat**, while
WeChat is logged in. Elevated (admin) WeChat may require the bridge to run
elevated too; otherwise `OpenProcess` fails with access denied, reported in
`errors` in the scan response rather than crashing.

The default CORS allowlist covers the Vite dev server (`:5173`) and the
compose frontend on localhost (`:3001`). If the page is opened via a LAN IP
or hostname — or the browser still reports `Failed to fetch` — see
[Troubleshooting: "Failed to fetch"](#troubleshooting-failed-to-fetch).

## Troubleshooting: "Failed to fetch"

`Failed to fetch` means the browser aborted the request **before** the bridge
answered. With the bridge running, this is almost always one of:

1. **CORS allowlist mismatch** — the page's origin (URL bar, e.g.
   `http://192.168.0.216:3001`) is not in the bridge's allowlist, so the
   browser discards the response. Fix:

   ```bash
   bridge.exe --allow-origins "http://192.168.0.216:3001"
   ```

   Multiple origins are comma-separated. If the page address changes often,
   `--cors-any` trusts any page on this machine (loopback-bound service; only
   local pages can reach it — prefer the explicit list when you can).

2. **Private Network Access (Chrome/Edge)** — browsers preflight requests
   from a LAN/public page to `127.0.0.1` and demand
   `Access-Control-Allow-Private-Network: true`. The bridge sends this header
   since v0.1.1, so **download the current `bridge.exe`** — the old binary
   predating the fix cannot answer PNA preflights.

3. **Wrong URL/port** — the panel default is `http://127.0.0.1:8787`; make
   sure it matches the bridge's `--port` and that you did not change the
   base URL to `http://localhost` while the bridge binds IPv4 only.

4. **Bridge not actually on this machine** — the browser must run on the
   same Windows box as `bridge.exe` and WeChat. A phone or a second laptop
   cannot reach the loopback service.

The panel now prints the page origin and the exact restart command when the
connection fails, e.g.
`Failed to reach the local bridge at http://127.0.0.1:8787. ... Restart the bridge with: bridge.exe --allow-origins "http://192.168.0.216:3001" ...`.

## HTTP API

| Endpoint                     | Method | Purpose |
|------------------------------|--------|---------|
| `/api/health`                | GET    | liveness, version, `scanSupported` (platform), active patterns |
| `/api/dbs`                   | GET    | discover chat-log databases under `xwechat_files` / `WeChat Files` (4.0.x: `MSG.db`; 4.1.x: `msg_*.db` / `message_*.db` / `biz_message_*.db`; Windows also probes every drive root) |
| `/api/scan`                  | GET    | scan WeChat-family process memory for configured patterns (501 on non-Windows) |
| `/api/key/validate`          | POST   | `{key, dbPath}` → `{valid, mode, salt, numPages}` via HMAC check |
| `/api/key/autofind`          | POST   | `{dbPath?}` → auto-discover the key from memory (see below) |
| `/api/key/tool`              | POST   | `{dllPath?, dbPath?}` → WeChat 4.1.x key via native probe-driven memory scan (DLL only as explicit fallback, see below) |
| `/api/media/keys`            | POST   | `{dir?, dllPath?, xorKey?, aesKey?}` → `.dat` XOR/AES decrypt keys (see below) |
| `/api/export`                | POST   | `{key, dbPath?, includeMedia?, xorKey?, aesKey?, dllPath?, accountDir?}` → base64 chat-export ZIP (see below) |
| `/api/logs`                  | GET    | `?after=<seq>` → recent bridge log lines + `next` cursor (incremental polling) |

All responses use `{"ok":true,"data":...}` / `{"ok":false,"error":{...}}`.
`valid:false` is a normal (HTTP 200) validation outcome, not an error.

`key` accepts the same shapes as `util.ParseKeyInput`: 64-hex (raw key or
passphrase — both modes are probed), 96-hex (key+salt from 4.0.x memory
scans), `x'...'` wrapped, or a path to a `wcdb-key-tool`/CipherTalk JSON cache
file. `dbPath` may be empty to auto-pick the first discovered database.

### `/api/key/autofind` — recover the key automatically

The 64-hex key never has to be typed. The bridge:

1. Reads the real per-install salt straight out of the chat database (for
   SQLCipher-4 the salt is the first 16 bytes of page 1 — readable without
   any key; works on 4.0.x `MSG.db` and 4.1.x `message_0.db`/`msg_0.db`
   alike) and builds **per-install scan patterns** from it, falling back to
   the community `wechat-4x-salt` anchor when they differ.
2. Scans WeChat process memory for the salt; around every hit it extracts
   32-byte **raw-key candidates** (the window immediately before the salt is
   tried first, then a byte-by-byte sweep) plus printable-ASCII runs as
   **passphrase candidates**.
3. Verifies every candidate against the actual database: raw keys first via
   the fast page-1 HMAC, and if that fails the page-1 **magic probe**
   (AES-CBC decrypt → `SQLite format 3`, independent of HMAC parameters);
   a key that only passes the magic probe is returned with
   `verification:"magic"` (export then opens the DB leniently). Passphrases
   go through PBKDF2-HMAC-SHA512 (slower, capped). A candidate must
   genuinely decrypt the database to be returned.

Response: `{found, key, mode, verification, salt, dbPath, attempts, hits, saltDumps, reason}`.
`found:false` + `reason` explains what was tried, e.g. when no WeChat process
is running ("start WeChat, then retry"), or no DB could be located ("provide
dbPath"). `saltDumps` carries hex windows around every memory hit for
diagnosing layouts the heuristic does not match. If the salt anchor pattern
also misses, restart the bridge with `--patterns` supplying a newer
community salt for your WeChat version (see "Memory scan patterns").

**WeChat 4.1.x caveat** — the classic scan usually finds **no key**: since
4.1 the database key is materialized by `weixin.dll`'s keystream into a
`global_config` structure, not kept next to a salt in memory. The supported
recovery path for 4.1.x is `/api/key/tool` below.

### `/api/key/tool` — WeChat 4.1.x key, natively (no external tool)

WeChat 4.1.x (`msg_*.db` / `message_*.db`) key recovery requires reading the
derived `db_key` out of the running WeChat. The bridge re-implements the
scanner in-project (`internal/bridge/keynative.go`) — no `wechat_key_tool.dll`
and no external dependencies — as a **probe-driven memory scan**:

1. Streams the readable address space of the running WeChat (Weixin.exe):
   every printable-ASCII run is split into hex spans, and every 64-hex window
   is tried as a 32-byte page key; chunks that also contain the account id
   (wxid) get a raw 32-byte window sweep (4.1 `global_config` may store the
   key as raw bytes near the account text).
2. Every candidate is verified against the actual chat-log database before
   being accepted: strict HMAC (`verification: mac`) first, then the page-1
   magic probe / lenient open (`verification: magic`). A wrong candidate can
   never pass, so the result is exact — version-independent, no offsets.
3. The CipherTalk DLL (CC BY-NC-SA, not redistributed) remains available only
   as an explicit opt-in fallback for API callers that pass `dllPath` and
   happen to have a CipherTalk install on the machine.

Request: `{dllPath?, dbPath?}`. Response: `{found, key, mode, verification,
dbPath, salt, dllPath, account, attempts, reason}` with `found:false` +
`reason` when WeChat is not running/logged in (start it and open a chat so
the databases are touched, then retry) or the key does not decrypt the DB.

### `/api/media/keys` — image/audio (.dat) decrypt keys

WeChat caches images and voice messages as `*.dat` files under the account
dir (`msg/attach/...`). The `datdecrypt` Go port of CipherTalk's
`datDecryptCore.ts` handles the containers:

| version | header | key |
|---|---|---|
| V1 | `07 08 56 31 08 07` | constant AES-128-ECB key (`"cfcd208495d565ef"` = first 16 hex chars of MD5(`"0"`)) |
| V2 | `07 08 56 32 08 07` | per-account AES-128-ECB key (16 ASCII chars) |
| V3 | none | whole-file XOR; key derivable from the header (JPEG/PNG/GIF/BMP/WebP signature) |

`POST /api/media/keys` with `{dir?, xorKey?, aesKey?}`:

1. Scans `dir` (auto = the account dir of the first discovered database)
   for `_t.dat` template files; logs the per-version counts.
2. Derives the **XOR key** from the templates (CipherTalk's majority-vote
   check `x ^ 0xFF == y ^ 0xD9`) — this alone decrypts V1/V3 media.
3. The **AES key** (only V2 media needs it) is recovered natively: the bridge
   grabs the 16-byte ciphertext from a V2 template and scans WeChat memory
   for the 32-char key string — every printable 16-char window is tried
   against the ciphertext (block magic) and confirmed with a full template
   decrypt, so again no DLL and no false positives.
4. `reason` always carries the details (e.g. "AES key not found in WeChat
   memory — open an image chat first so the key is loaded, then retry").
   `verification` is `template-decrypt | block-magic`, or
   `none (V1/V3 only; constant or header-derived XOR decrypts these)` when no
   V2 template files exist at all — in that case `AES --` in the panel is the
   correct outcome, not an error.

Manual overrides (`xorKey` hex, `aesKey` 16 ASCII chars, or the bridge flags
`--media-xor-key` / `--media-aes-key`) are honored when auto-detection fails.

### `/api/media/decrypt` and `/api/media/decrypt-dir` — decrypt media

* `POST /api/media/decrypt {path, xorKey?, aesKey?}` — one `.dat` file →
  `{data (base64), ext, size, version, xorKey}`. XOR key is derived from the
  file header when not given.
* `POST /api/media/decrypt-dir {dir, xorKey?, aesKey?, includePlain?}` —
  walks `dir`, decrypts every `.dat` (V1/V3 with constant/header keys and
  optional manual AES; V2 with the account AES key), sniffs the extension,
  copies non-media files as-is, and returns a ZIP
  (`wechat-media-decrypt.zip`, base64) with the original relative paths. Up
  to 256 MB of output; per-file failures are reported in `reasons`.

Same core is available standalone: `cd exporter && go run ./cmd/wedat
-decrypt file.dat -xor 73 [-aes 0123456789abcdef] > out.bin`.

### `/api/export` — produce the upload ZIP

With the verified key, the bridge decrypts the chat database(s) (SQLCipher
4), locates the chat-log tables, and builds the exact document the backend
upload step consumes:

- **WeChat 4.0.x** (`MSG.db`): a single `MSG`/`message` table.
- **WeChat 4.1.x**: the chat log is split into `msg_*.db` / `message_*.db` /
  `biz_message_*.db` files, and each conversation lives in its own table
  named `msg_<md5(session)>`. The export merges **all** per-session tables
  from the chosen database, resolves senders via the `Name2Id` table
  (`real_sender_id` rowid → wxid), and skips tombstone rows.

Column mapping is schema-introspected and heuristic over both generations
(`createTime`/`create_time`, `message_content`/`content`, `talker`/`sender`,
`is_send`/`isSend`, …):

```json
{"session":{"nickname":"wxid_...","messageCount":N},"messages":[
  {"createTime":1750000000,"formattedTime":"2025-06-16 08:26:40","type":"图片消息",
   "content":"[图片] images/20250615/1750000100_aabbccddeeff00112233445566778899.jpg",
   "isSend":1,"senderUsername":"wxid_...","source":"wechat-extract"}, ...]}
```

wrapped in a ZIP (`messages.json` at the ZIP root), base64-encoded in the
response. Types are normalized to the Chinese display names the backend
recognizes (`1 文本消息`, `3 图片消息`, `34 语音消息`, `47 动画表情`,
`10000 系统消息`, …).

#### `includeMedia: true` — embed the images

When requested, the bridge also resolves the image `.dat` files (CipherTalk's
`imageDecryptService` path layout: `msg/attach/<session>/<YYYY-MM>/Img|Image|
mg|MsgImg/`, filename variants `<md5>{,_h,.h,_hd,_t,.t,_thumb}.dat`):

1. The **XOR key** is derived from `_t.dat` templates (majority-vote
   `x ^ 0xFF == y ^ 0xD9`) or the request's `xorKey`.
2. The **AES key** (V2 files) is read from WeChat memory via the key-tool
   DLL (`wkt_scan_image_key_auth`) when not supplied; keep WeChat running and
   have opened the images once so the thumbnails exist in memory.
3. Each image message's `content` becomes `"[图片] images/<YYYYMMDD>/<ts>_<md5>.<ext>"`
   and the decrypted bytes are written into the ZIP at exactly that relative
   path — the backend's extraction (it hashes the path after the leading
   bracket and requires the file to exist there) picks them up automatically.

Response adds `mediaCount`, `mediaFailed`, `accountDir`, `mediaReason`
(explains which keys were used/why media was skipped). Voice/video/emoji
export is **not** implemented yet — those are logged as skips; only images
are embedded.

### `/api/logs` — bridge activity log

Every bridge action (health, DB discovery, key recovery, media keys, export)
writes a timestamped line into an in-memory ring buffer, mirrored to stdout.
`GET /api/logs?after=<seq>` returns `{lines:[{seq,ts,level,msg}], next}` —
the frontend polls it every ~2 s with the last `next` cursor for a live
"what is the bridge doing" feed (the log endpoint itself is not logged).

The same core is available as a standalone CLI: `cd exporter && go run
./cmd/extract -db message_0.db -key <hex> -out chat.json [-zip chat.zip]`.

## Memory scan patterns

The engine searches process memory for byte patterns; hits are returned with
address + context for inspection. Built-ins are conservative anchors:

* `wechat-4x-salt` — community-reported WeChat 4.0.x WCDB page-1 salt
  (verify against your own DB; value may change across versions)
* `wcdb` — WCDB engine marker string

Version-specific key signatures are community-maintained; supply them without
rebuilding via a JSON file (`--patterns`):

```json
{
  "patterns": [
    {"name": "my-version-pattern", "description": "...", "hex": "deadbeef"},
    "deadbeef"
  ]
}
```

Hits must still pass `/api/key/validate` to be trusted — that is the
cryptographic backstop, so stale or wrong patterns simply yield no verified
key instead of corrupting anything.

## Why not scan memory from the browser directly?

* No web API exposes another process's virtual address space; `WebAssembly`
  memory is confined to the module's own sandbox.
* Site isolation partitions memory per origin; `SharedArrayBuffer` only shares
  between threads of the same page.
* `performance.memory` reports only the page's own heap.
* File System Access API can only read files the user explicitly grants.

The only browser-owned way to reach the OS is an extension with
`nativeMessaging`, which still requires installing a native host — the bridge
above gives the same capability with a plain loopback HTTP server and no
extension.

## Distribution to Windows users (download link)

The backend serves the prebuilt Windows binary so users get a real download
link instead of building from source:

1. **Build once** (any OS — Go cross-compiles):

   ```bash
   cd exporter
   GOOS=windows GOARCH=amd64 go build -o ../tools/bridge.exe ./cmd/bridge
   ```

2. **Publish**: put `bridge.exe` in the backend's `storage.tools-dir`
   (default `./tools` — in Docker this is the `./tools:/app/tools` volume
   already declared in `compose.yaml`). The binary is gitignored on purpose;
   the directory is not deployed with the app.

3. **Download link**: the frontend panel checks
   `GET <API_BASE_URL>/api/tools/bridge/meta` and shows a
   "Download bridge.exe" button pointing at
   `GET <API_BASE_URL>/api/tools/bridge/download`
   (`Content-Disposition: attachment`, `X-Bridge-SHA256` header). When the
   file is missing, the endpoint returns 404 with build instructions in the
   response body.

4. **Users verify integrity** (PowerShell, on the Windows machine):

   ```powershell
   (Get-FileHash bridge.exe -Algorithm SHA256).Hash
   ```

   Compare with the SHA-256 shown in the panel. Unsigned binaries trigger a
   SmartScreen/AV warning — expected for an internal tool; click
   "More info → Run anyway". Code signing (EV cert) only matters if this is
   distributed publicly.

Rebuild + redeploy the binary (step 1–2) whenever `cmd/bridge` changes; the
frontend does not need rebuilding for a new binary. The binary is not
committed to git — treat `tools/` like `uploads/`.

## Frontend wiring

`frontend/src/components/panels/LocalScanPanel.jsx` + `frontend/src/api/
localBridge.js` implement the UI — deliberately kept to four actions plus a
live log, nothing else:

1. **Get the WeChat database key** — click "Get DB key"; the bridge scans the
   running WeChat memory itself (no external tool), verifies the key against
   the database below, and fills it in (copyable).
2. **Get the media decryption keys** — derives the `.dat` XOR key from the
   templates and reads the V2 AES key from WeChat memory itself (keep WeChat
   running; open an image chat once so `_t.dat` thumbnails exist and the key
   is loaded).
3. **Export chat + media ZIP** — decrypts the chat log with the DB key and
   bundles `messages.json` + the decrypted image files at exactly the
   relative paths `content` references (`[图片] images/<date>/<ts>_<md5>.jpg`),
   ready for the upload step.
4. **Bridge activity log** — polls `/api/logs` every ~2 s; every bridge
   action shows up here (key recovery, verification, media scan, export
   counters, errors), so it is always clear what happened and why.

The bridge URL and optional token are persisted in `localStorage`
(`VITE_LOCAL_BRIDGE_URL` overrides the default `http://127.0.0.1:8787` at
build time). The memory-scan and manual key-verification sections were
removed as redundant — verification now happens inside the key-tool step.

Requires bridge **v0.1.8** or newer: native (DLL-free) DB/AES key recovery, media-in-export + logs, per-level bridge log panel (--log-level debug).