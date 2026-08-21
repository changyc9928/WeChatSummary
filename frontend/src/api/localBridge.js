import { LOCAL_BRIDGE_URL } from '../config';

/**
 * Minimal client for the local sidecar (wechat-key-bridge, exporter/cmd/bridge).
 * The browser cannot scan OS memory; this service runs on 127.0.0.1 and does
 * the scanning/validation on the frontend's behalf.
 */

function normalizeBase(base) {
  return String(base || LOCAL_BRIDGE_URL).replace(/\/+$/, '');
}

async function bridgeRequest({ baseUrl = LOCAL_BRIDGE_URL, token = '', path, method = 'GET', body }) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body) headers['Content-Type'] = 'application/json';

  let res;
  try {
    res = await fetch(`${normalizeBase(baseUrl)}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined
    });
  } catch (err) {
    // "Failed to fetch" almost always means the browser blocked the request
    // before the bridge answered: the page origin is missing from the bridge's
    // CORS allowlist, or Chrome/Edge Private Network Access rejected the
    // page -> 127.0.0.1 call. Give the operator the exact restart command.
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const fix = origin
      ? `The bridge is probably running, but the browser blocked this page (${origin}) from talking to ${baseUrl}. Restart the bridge with: bridge.exe --allow-origins "${origin}" (or --cors-any if the page address changes often).`
      : 'Make sure bridge.exe is running on this machine (default port 8787) and the URL above matches its --port.';
    throw new Error(`Failed to reach the local bridge at ${baseUrl}. ${fix} (${err.message})`);
  }

  let payload = null;
  try {
    payload = await res.json();
  } catch {
    // non-JSON body (unexpected)
  }

  if (!res.ok || !payload || payload.ok === false) {
    const msg = payload?.error?.message || (payload?.error?.code ? `Bridge error: ${payload.error.code}` : `HTTP ${res.status}`);
    throw new Error(msg);
  }
  return payload.data;
}

export const localBridge = {
  health: ({ baseUrl, token } = {}) => bridgeRequest({ baseUrl, token, path: '/api/health' }),
  findDbs: ({ baseUrl, token } = {}) => bridgeRequest({ baseUrl, token, path: '/api/dbs' }),
  scan: ({ baseUrl, token } = {}) => bridgeRequest({ baseUrl, token, path: '/api/scan' }),
  validateKey: ({ baseUrl, token, key, dbPath } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/key/validate',
      method: 'POST',
      body: { key, dbPath: dbPath || undefined }
    }),
  autoFindKey: ({ baseUrl, token, dbPath } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/key/autofind',
      method: 'POST',
      body: { dbPath: dbPath || undefined }
    }),
  keyTool: ({ baseUrl, token, dllPath, dbPath } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/key/tool',
      method: 'POST',
      body: { dllPath: dllPath || undefined, dbPath: dbPath || undefined }
    }),
  exportChat: ({ baseUrl, token, key, dbPath, includeMedia, xorKey, aesKey, dllPath, accountDir, tables, from, to, outDir } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/export',
      method: 'POST',
      body: {
        key,
        dbPath: dbPath || undefined,
        includeMedia: !!includeMedia,
        xorKey: xorKey || undefined,
        aesKey: aesKey || undefined,
        dllPath: dllPath || undefined,
        accountDir: accountDir || undefined,
        tables: tables && tables.length ? tables : undefined,
        from: from || undefined,
        to: to || undefined,
        outDir: outDir || undefined
      }
    }),
  exportSessions: ({ baseUrl, token, key, dbPath } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/export/sessions',
      method: 'POST',
      body: { key, dbPath: dbPath || undefined }
    }),
  logs: ({ baseUrl, token, after = 0 } = {}) =>
    bridgeRequest({ baseUrl, token, path: `/api/logs?after=${after}` }),
  setLogWebhook: ({ baseUrl, token, url } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/log/webhook',
      method: 'POST',
      body: { url: url || undefined }
    }),
  getLogWebhook: ({ baseUrl, token } = {}) =>
    bridgeRequest({ baseUrl, token, path: '/api/log/webhook' }),
  mediaKeys: ({ baseUrl, token, dir, dllPath, xorKey, aesKey } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/media/keys',
      method: 'POST',
      body: { dir: dir || undefined, dllPath: dllPath || undefined, xorKey: xorKey || undefined, aesKey: aesKey || undefined }
    }),
  mediaDecrypt: ({ baseUrl, token, path, xorKey, aesKey } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/media/decrypt',
      method: 'POST',
      body: { path, xorKey: xorKey || undefined, aesKey: aesKey || undefined }
    }),
  mediaDecryptDir: ({ baseUrl, token, dir, xorKey, aesKey } = {}) =>
    bridgeRequest({
      baseUrl,
      token,
      path: '/api/media/decrypt-dir',
      method: 'POST',
      body: { dir, xorKey: xorKey || undefined, aesKey: aesKey || undefined }
    })
};