import { useEffect, useRef, useState } from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';
import { localBridge } from '../../api/localBridge';
import { apiClient } from '../../api/client';
import { LOCAL_BRIDGE_URL, API_BASE_URL } from '../../config';

const TOKEN_KEY = 'wechat_bridge_token';
const URL_KEY = 'wechat_bridge_url';

function fmtBytes(n) {
  if (!Number.isFinite(n)) return String(n ?? 0);
  const units = ['B', 'KB', 'MB', 'GB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v.toFixed(v < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

function base64ToBlob(b64, type) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type });
}

function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

const LOG_LEVEL_COLOR = { error: '#dc2626', warn: '#b45309', info: '#374151', debug: '#6b7280' };

/**
 * Local key bridge panel — four things only:
 *  1. Get the WeChat DB decryption key: the bridge scans the running WeChat
 *     (Weixin.exe) memory itself (no external DLL) and verifies the key
 *     against the selected database.
 *  2. Get the media (image .dat) decryption keys (XOR from templates; the
 *     V2-only AES key is also read from WeChat memory by the bridge).
 *  3. Export the chat + images as one ZIP (the JSON is named after the
 *     resolved chat display name, e.g. "相亲相爱的coser们.json", with
 *     relative media paths — exactly like the uploads/ examples).
 *  4. Live bridge activity log.
 */
export default function LocalScanPanel() {
  const { t } = useLanguage();
  const [baseUrl, setBaseUrl] = useState(() => localStorage.getItem(URL_KEY) || LOCAL_BRIDGE_URL);
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || '');

  const [health, setHealth] = useState(null);
  const [healthError, setHealthError] = useState('');
  const [dbs, setDbs] = useState([]);
  const [error, setError] = useState('');

  const [toolMeta, setToolMeta] = useState(null);
  const [toolState, setToolState] = useState('checking'); // checking | present | missing | unreachable

  const [dbPath, setDbPath] = useState('');
  const [dbKey, setDbKey] = useState('');
  const [dbKeyInfo, setDbKeyInfo] = useState(null); // {found, mode, verification, reason}
  const [mediaKeys, setMediaKeys] = useState(null);
  const [exportDone, setExportDone] = useState(null);

  // chat-group selection + date-range filters for step 3
  const [sessions, setSessions] = useState(null); // null = not loaded; [] = loaded but empty
  const [sessionError, setSessionError] = useState('');
  const [sessionWarnings, setSessionWarnings] = useState([]);
  const [exportWarnings, setExportWarnings] = useState([]);
  const [selectedTables, setSelectedTables] = useState({}); // table -> true when selected
  const [fromDate, setFromDate] = useState(''); // yyyy-mm-dd (empty = all)
  const [toDate, setToDate] = useState(''); // yyyy-mm-dd (empty = all)
  const [exportPath, setExportPath] = useState(''); // on-disk zip path from the bridge
  const [exportSize, setExportSize] = useState(0);

  const [busy, setBusy] = useState({ health: false, dbs: false, keyTool: false, mediaKeys: false, export: false, sessions: false });

  const [logs, setLogs] = useState([]);
  const [logPaused, setLogPaused] = useState(false);
  const [logStreamError, setLogStreamError] = useState('');
  const [logLevel, setLogLevel] = useState('all'); // all | info | warn | error
  const logCursor = useRef(0);

  // Server-side bridge log mirror (--log-webhook -> backend). Lets the user
  // (and the developer) inspect a full run without copy-pasting the console.
  const [serverLogs, setServerLogs] = useState([]);
  const [serverLogCursor, setServerLogCursor] = useState(0);
  const [serverLogError, setServerLogError] = useState('');
  const [serverLogVisible, setServerLogVisible] = useState(false);
  const [serverLogCopied, setServerLogCopied] = useState(false);

  useEffect(() => {
    if (!serverLogVisible) return undefined;
    let cancelled = false;
    const tick = async () => {
      try {
        const res = await fetch(`${API_BASE_URL.replace(/\/+$/, '')}/api/tools/bridge/logs?after=${serverLogCursor}`, {
          headers: { Accept: 'application/json' }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const env = await res.json();
        const data = env?.data;
        if (!cancelled && data) {
          setServerLogError('');
          if (Array.isArray(data.lines) && data.lines.length) {
            setServerLogs(prev => [...prev, ...data.lines].slice(-1500));
            setServerLogCursor(Number(data.next) || serverLogCursor);
          }
        }
      } catch (err) {
        if (!cancelled) setServerLogError(err.message);
      }
    };
    tick();
    const id = setInterval(tick, 2000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [serverLogVisible, serverLogCursor]);

  const copyServerLogs = async () => {
    try {
      const text = serverLogs
        .map(l => `${l.ts} [${l.level}] ${l.msg}`)
        .join('\n');
      await navigator.clipboard.writeText(text);
      setServerLogCopied(true);
      setTimeout(() => setServerLogCopied(false), 1500);
    } catch {
      // clipboard unavailable; nothing to do
    }
  };

  const LOG_RANK = { debug: 0, info: 1, warn: 2, error: 3 };

  const [copied, setCopied] = useState(''); // which value was copied

  useEffect(() => {
    localStorage.setItem(URL_KEY, baseUrl);
  }, [baseUrl]);
  useEffect(() => {
    localStorage.setItem(TOKEN_KEY, token);
  }, [token]);

  // Auto-configure the server-log webhook: ask the bridge where its log
  // webhook points; if it isn't forwarding to this backend yet, point it at
  // our /api/tools/bridge/logs so every run is mirrored server-side without
  // the --log-webhook flag. Best-effort: an old bridge.exe without the
  // endpoint just fails the call silently.
  const [webhookState, setWebhookState] = useState('checking'); // checking | on | off | error
  const autoConfigureWebhook = async () => {
    try {
      const backendLogURL = `${API_BASE_URL.replace(/\/+$/, '')}/api/tools/bridge/logs`;
      const cur = await localBridge.getLogWebhook({ baseUrl, token });
      if (cur?.enabled && cur?.url === backendLogURL) {
        setWebhookState('on');
        return;
      }
      if (cur?.enabled && cur?.url && cur.url !== backendLogURL) {
        // Bridge is forwarding elsewhere; leave it alone but surface it.
        setWebhookState('off');
        return;
      }
      await localBridge.setLogWebhook({ baseUrl, token, url: backendLogURL });
      setWebhookState('on');
    } catch {
      setWebhookState('error'); // old bridge.exe or bridge unreachable
    }
  };
  useEffect(() => {
    autoConfigureWebhook();
  }, [baseUrl, token]);

  const setBusyField = (field, value) => setBusy(prev => ({ ...prev, [field]: value }));

  const checkToolMeta = async () => {
    setToolState('checking');
    try {
      const envelope = await apiClient.tools.meta();
      setToolMeta(envelope.data || null);
      setToolState(envelope.data?.present ? 'present' : 'missing');
    } catch {
      setToolState('unreachable');
    }
  };

  useEffect(() => {
    checkToolMeta();
  }, []);

  // Live log polling: every 2s fetch lines after the last seen cursor.
  useEffect(() => {
    if (logPaused) return undefined;
    let cancelled = false;
    const tick = async () => {
      try {
        const data = await localBridge.logs({ baseUrl, token, after: logCursor.current });
        if (!cancelled && data) {
          setLogStreamError('');
          if (Array.isArray(data.lines) && data.lines.length) {
            setLogs(prev => [...prev, ...data.lines].slice(-500));
            logCursor.current = data.next || logCursor.current;
          }
        }
      } catch (err) {
        // Bridge down, CORS-blocked, or an old bridge.exe without /api/logs.
        // Show the actual reason instead of failing silently, then keep polling.
        if (!cancelled) {
          setLogStreamError(prev => (prev === err.message ? prev : err.message));
        }
      }
    };
    tick();
    const id = setInterval(tick, 2000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [baseUrl, token, logPaused]);

  const handleHealth = async () => {
    setBusyField('health', true);
    setError('');
    setHealthError('');
    try {
      setHealth(await localBridge.health({ baseUrl, token }));
    } catch (err) {
      setHealth(null);
      setHealthError(err.message);
    } finally {
      setBusyField('health', false);
    }
  };

  const handleFindDbs = async () => {
    setBusyField('dbs', true);
    setError('');
    try {
      const data = await localBridge.findDbs({ baseUrl, token });
      setDbs(data.databases || []);
      if (data.databases?.length === 1) setDbPath(data.databases[0]);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyField('dbs', false);
    }
  };

  const copyToClipboard = async (label, value) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
      setTimeout(() => setCopied(prev => (prev === label ? '' : prev)), 1500);
    } catch {
      // clipboard unavailable; nothing to do
    }
  };

  const copyButton = (label, value) => (
    <button
      onClick={() => copyToClipboard(label, value)}
      style={{ ...styles.buttonSecondary, padding: '2px 8px', fontSize: '0.72rem' }}
    >
      {copied === label ? t('localBridge.copied') : t('localBridge.copy')}
    </button>
  );

  // 1 · Get the WeChat DB key (CipherTalk key tool DLL, user-supplied).
  const handleGetDbKey = async () => {
    setBusyField('keyTool', true);
    setError('');
    setDbKeyInfo(null);
    setDbKey('');
    try {
      const data = await localBridge.keyTool({ baseUrl, token, dbPath: dbPath.trim() });
      setDbKeyInfo(data);
      if (data.found) {
        setDbKey(data.key);
        if (data.dbPath) setDbPath(data.dbPath);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyField('keyTool', false);
    }
  };

  // 2 · Get the media decryption keys (XOR from _t.dat templates, AES via DLL).
  const handleGetMediaKeys = async () => {
    setBusyField('mediaKeys', true);
    setError('');
    setMediaKeys(null);
    try {
      const data = await localBridge.mediaKeys({ baseUrl, token });
      setMediaKeys(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyField('mediaKeys', false);
    }
  };

  // 3 · List chat groups (tables) + time ranges so the user can pick what to export.
  const handleLoadSessions = async () => {
    setBusyField('sessions', true);
    setError('');
    setSessionError('');
    setSessionWarnings([]);
    setExportWarnings([]);
    setExportDone(null);
    setExportPath('');
    try {
      const data = await localBridge.exportSessions({ baseUrl, token, key: dbKey.trim(), dbPath: dbPath.trim() });
      const list = data?.sessions || [];
      setSessions(list);
      setSessionWarnings(data?.warnings || []);
      // default: all chats selected
      const sel = {};
      list.forEach(s => { sel[s.table] = true; });
      setSelectedTables(sel);
    } catch (err) {
      setSessionError(err.message);
      setSessions(null);
    } finally {
      setBusyField('sessions', false);
    }
  };

  // 3b · Export the selected chats (+date range) as one ZIP with image media.
  const handleExport = async () => {
    setBusyField('export', true);
    setError('');
    setExportWarnings([]);
    setExportDone(null);
    setExportPath('');
    setExportSize(0);
    const tables = Object.keys(selectedTables).filter(t => selectedTables[t]);
    const from = fromDate ? Math.floor(new Date(`${fromDate}T00:00:00`).getTime() / 1000) : 0;
    const to = toDate ? Math.floor(new Date(`${toDate}T23:59:59`).getTime() / 1000) : 0;
    try {
      const data = await localBridge.exportChat({
        baseUrl,
        token,
        key: dbKey.trim(),
        dbPath: dbPath.trim(),
        includeMedia: true,
        xorKey: mediaKeys?.xorKey || undefined,
        aesKey: mediaKeys?.aesKey || undefined,
        tables: tables.length ? tables : undefined,
        from,
        to
      });
      if (data.zipPath) {
        // Large export: the bridge wrote the ZIP to disk; show the path and
        // offer a direct download through the bridge (avoids 445MB base64).
        setExportPath(data.zipPath);
        setExportSize(data.zipSize || 0);
      } else if (data.zipBase64) {
        downloadBlob(base64ToBlob(data.zipBase64, 'application/zip'), data.fileName || 'wechat-chat-export.zip');
      }
      setExportDone({
        fileName: data.fileName || 'wechat-chat-export.zip',
        count: data.messageCount,
        media: data.mediaCount,
        failed: data.mediaFailed,
        reason: data.mediaReason,
        shards: data.shards,
        warnings: data.warnings || [],
        fromDate: fromDate || null,
        toDate: toDate || null
      });
      setExportWarnings(data.warnings || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyField('export', false);
    }
  };

  const sessionCount = sessions ? sessions.reduce((n, s) => n + (Number(s.count) || 0), 0) : 0;
  const dateToInput = ts => (ts > 0 ? new Date(ts * 1000).toISOString().slice(0, 10) : '');

  const online = health?.service === 'wechat-key-bridge';
  const mediaReady = !!(mediaKeys?.found && mediaKeys.xorKey);
  // Flag stale bridge builds: the log panel (--log-level, reasons) needs 0.1.8+.
  const verParts = (health?.version || '').split('.').map(n => parseInt(n, 10) || 0);
  const isOldBridge =
    online && verParts.length === 3 &&
    (verParts[0] < 0 ||
      (verParts[0] === 0 && verParts[1] < 1) ||
      (verParts[0] === 0 && verParts[1] === 1 && verParts[2] < 8));

  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>{t('localBridge.title')}</h3>
        <span
          style={{ ...styles.lockBadge, color: online ? (isOldBridge ? '#b45309' : '#059669') : 'inherit' }}
          title={isOldBridge ? t('localBridge.healthOldVersion', { version: health?.version }) : ''}
        >
          {online
            ? `● ${t('localBridge.healthOk')} · v${health?.version}${isOldBridge ? ' ⚠' : ''}`
            : t('localBridge.healthBad')}
        </span>
      </div>
      <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--text-muted, #666)' }}>{t('localBridge.subtitle')}</p>

      <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'end' }}>
        <div style={{ flex: '1 1 240px' }}>
          <label style={styles.label}>{t('localBridge.urlLabel')}</label>
          <input
            value={baseUrl}
            onChange={e => setBaseUrl(e.target.value)}
            style={styles.select}
            spellCheck={false}
          />
        </div>
        <div style={{ flex: '1 1 180px' }}>
          <label style={styles.label}>{t('localBridge.tokenLabel')}</label>
          <input
            type="password"
            value={token}
            onChange={e => setToken(e.target.value)}
            style={styles.select}
            placeholder="Bearer token"
          />
        </div>
        <button onClick={handleHealth} disabled={busy.health} style={styles.buttonSecondary}>
          {busy.health ? '...' : t('localBridge.check')}
        </button>
        <button onClick={handleFindDbs} disabled={busy.dbs} style={styles.buttonSecondary}>
          {busy.dbs ? '...' : t('localBridge.findDbs')}
        </button>
      </div>

      {healthError && <div style={styles.errorText}>⚠️ {healthError}</div>}
      {error && <div style={styles.errorText}>⚠️ {error}</div>}

      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap', padding: '10px 12px', background: 'var(--bg-subtle, #f7f7f7)', borderRadius: '8px' }}>
        <span style={{ fontSize: '0.85rem', fontWeight: '600', color: 'var(--text-primary, #111)' }}>
          {t('localBridge.downloadSection')}
        </span>
        {toolState === 'checking' && <span style={{ fontSize: '0.82rem', color: '#666' }}>{t('localBridge.downloadChecking')}</span>}
        {toolState === 'present' && toolMeta && (
          <>
            <a
              href={`${API_BASE_URL.replace(/\/+$/, '')}${toolMeta.downloadUrl}?v=${toolMeta.sha256}`}
              target="_blank"
              rel="noreferrer"
              style={styles.buttonSuccess}
            >
              ⬇ {t('localBridge.download')}
            </a>
            <span style={{ fontSize: '0.78rem', color: '#666', fontFamily: 'monospace' }}>
              {fmtBytes(toolMeta.sizeBytes)} · SHA-256 {toolMeta.sha256?.slice(0, 16)}…
            </span>
          </>
        )}
        {toolState === 'missing' && (
          <>
            <span style={{ fontSize: '0.82rem', color: '#b45309' }}>{t('localBridge.downloadMissing')}</span>
            <span style={{ fontSize: '0.72rem', color: '#666', maxWidth: '520px' }}>{toolMeta?.help}</span>
          </>
        )}
        {toolState === 'unreachable' && (
          <span style={{ fontSize: '0.82rem', color: '#b45309' }}>{t('localBridge.downloadUnreachable')}</span>
        )}
        {(toolState === 'missing' || toolState === 'unreachable') && (
          <button onClick={checkToolMeta} style={styles.refreshButton}>{t('localBridge.downloadRefresh')}</button>
        )}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', padding: '10px 12px', border: '1px solid var(--border, #eee)', borderRadius: '8px' }}>
        <label style={styles.label}>{t('localBridge.dbLabel')}</label>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
          <input
            value={dbPath}
            onChange={e => setDbPath(e.target.value)}
            style={{ ...styles.select, flex: '1 1 420px', fontFamily: 'monospace' }}
            placeholder={dbs.length === 0 ? t('localBridge.dbPathExample') : t('localBridge.dbPlaceholder')}
            spellCheck={false}
          />
          {dbs.length > 0 && (
            <span style={{ fontSize: '0.75rem', color: '#666' }}>{t('localBridge.dbsFound', { n: dbs.length })}</span>
          )}
        </div>
        {dbs.length > 1 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
            {dbs.map(db => (
              <button
                key={db}
                onClick={() => setDbPath(db)}
                title={db}
                style={{
                  ...styles.buttonSecondary,
                  fontFamily: 'monospace',
                  fontSize: '0.72rem',
                  padding: '3px 8px',
                  background: db === dbPath ? 'var(--accent, #2563eb)' : undefined,
                  color: db === dbPath ? '#fff' : undefined
                }}
              >
                {db.split(/[\\/]/).pop()}
              </button>
            ))}
          </div>
        )}
        {dbs.length === 0 && health && (
          <span style={{ fontSize: '0.8rem', color: '#b45309', maxWidth: '720px' }}>{t('localBridge.dbManualHint')}</span>
        )}
      </div>

      {/* step 1: DB key */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', borderTop: '1px solid var(--border, #eee)', paddingTop: '14px' }}>
        <h4 style={{ margin: 0, fontSize: '0.95rem', color: 'var(--text-primary, #111)' }}>{t('localBridge.step1Title')}</h4>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <button onClick={handleGetDbKey} disabled={busy.keyTool} style={styles.buttonSuccess}>
            {busy.keyTool ? t('localBridge.keyToolRunning') : t('localBridge.getDbKeyButton')}
          </button>
          {!dbKey && !dbKeyInfo && (
            <span style={{ fontSize: '0.8rem', color: '#666' }}>{t('localBridge.keyToolHint')}</span>
          )}
        </div>
        {dbKeyInfo && (
          <div style={{ fontSize: '0.85rem', display: 'flex', flexDirection: 'column', gap: '6px' }}>
            {dbKeyInfo.found ? (
              <>
                <strong style={{ color: '#059669' }}>
                  ✓ {t('localBridge.keyToolFound', { mode: dbKeyInfo.mode, verification: dbKeyInfo.verification })}
                  {dbKeyInfo.account?.wxid ? ` · ${dbKeyInfo.account.wxid}` : ''}
                </strong>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                  <code style={{ fontFamily: 'monospace', fontSize: '0.8rem', background: 'var(--bg-subtle, #f7f7f7)', padding: '4px 8px', borderRadius: '6px', wordBreak: 'break-all' }}>
                    {dbKey}
                  </code>
                  {copyButton('dbKey', dbKey)}
                </div>
                {dbKeyInfo.reason && (
                  <span style={{ fontSize: '0.75rem', color: '#666', maxWidth: '720px' }}>{dbKeyInfo.reason}</span>
                )}
              </>
            ) : (
              <strong style={{ color: '#b45309' }}>
                ✗ {t('localBridge.keyToolNotFound', { reason: dbKeyInfo.reason || 'no key recovered' })}
              </strong>
            )}
            <span style={{ fontSize: '0.75rem', color: '#666' }}>{t('localBridge.dbKeyCaption')}</span>
          </div>
        )}
      </div>

      {/* step 2: media keys */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', borderTop: '1px solid var(--border, #eee)', paddingTop: '14px' }}>
        <h4 style={{ margin: 0, fontSize: '0.95rem', color: 'var(--text-primary, #111)' }}>{t('localBridge.step2Title')}</h4>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <button onClick={handleGetMediaKeys} disabled={busy.mediaKeys} style={styles.buttonSecondary}>
            {busy.mediaKeys ? t('localBridge.mediaKeysFinding') : t('localBridge.getMediaKeysButton')}
          </button>
          {mediaReady && (
            <span style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap', fontSize: '0.85rem' }}>
              <strong style={{ color: '#059669' }}>
                {t('localBridge.mediaKeysFound', { xorKey: mediaKeys.xorKey, aesKey: mediaKeys.aesKey || '—', verification: mediaKeys.verification || '—' })}
              </strong>
              {copyButton('xor', mediaKeys.xorKey)}
              {mediaKeys.aesKey && copyButton('aes', mediaKeys.aesKey)}
            </span>
          )}
          {mediaKeys && !mediaKeys.found && (
            <strong style={{ fontSize: '0.85rem', color: '#b45309' }}>
              ✗ {t('localBridge.mediaKeysNotFound', { reason: mediaKeys.reason || 'no templates' })}
            </strong>
          )}
        </div>
        {mediaKeys && mediaKeys.found && mediaKeys.reason && (
          <span style={{ fontSize: '0.75rem', color: '#666', maxWidth: '760px' }}>ℹ️ {mediaKeys.reason}</span>
        )}
        <span style={{ fontSize: '0.75rem', color: '#666', maxWidth: '760px' }}>{t('localBridge.mediaCaption')}</span>
      </div>

      {/* step 3: pick chats + date range, then export chat + media */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', borderTop: '1px solid var(--border, #eee)', paddingTop: '14px' }}>
        <h4 style={{ margin: 0, fontSize: '0.95rem', color: 'var(--text-primary, #111)' }}>{t('localBridge.step3Title')}</h4>

        {/* chat list loader */}
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <button onClick={handleLoadSessions} disabled={busy.sessions || !dbKey.trim() || !dbPath.trim()} style={styles.buttonSecondary}>
            {busy.sessions ? t('localBridge.sessionsLoading') : t('localBridge.sessionsButton')}
          </button>
          {sessionError && <span style={{ fontSize: '0.8rem', color: '#b45309' }}>⚠️ {sessionError}</span>}
          {sessions && (
            <span style={{ fontSize: '0.8rem', color: '#666' }}>
              {t('localBridge.sessionsSummary', { chats: sessions.length, count: sessionCount })}
            </span>
          )}
        </div>

        {/* WeChat-running warning (bridge reports un-checkpointed -wal) */}
        {sessionWarnings.length > 0 && (
          <div style={{ fontSize: '0.78rem', color: '#b45309', background: 'rgba(180,83,9,0.08)', border: '1px solid rgba(180,83,9,0.25)', borderRadius: '8px', padding: '8px 10px', maxWidth: '760px' }}>
            {sessionWarnings.map((msg, i) => <div key={i}>⚠️ {msg}</div>)}
          </div>
        )}

        {/* chat checkboxes */}
        {sessions && sessions.length > 0 && (
          <div
            style={{
              maxHeight: '190px',
              overflow: 'auto',
              border: '1px solid var(--border, #eee)',
              borderRadius: '8px',
              padding: '8px 10px',
              display: 'flex',
              flexDirection: 'column',
              gap: '2px'
            }}
          >
            <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '4px' }}>
              <button
                onClick={() => setSelectedTables(Object.fromEntries(sessions.map(s => [s.table, true])))}
                style={{ ...styles.buttonSecondary, fontSize: '0.72rem', padding: '2px 8px' }}
              >
                {t('localBridge.sessionsSelectAll')}
              </button>
              <button
                onClick={() => setSelectedTables(Object.fromEntries(sessions.map(s => [s.table, false])))}
                style={{ ...styles.buttonSecondary, fontSize: '0.72rem', padding: '2px 8px' }}
              >
                {t('localBridge.sessionsSelectNone')}
              </button>
              <span style={{ fontSize: '0.72rem', color: '#666' }}>
                ✓ {Object.keys(selectedTables).filter(t => selectedTables[t]).length} / {sessions.length}
              </span>
            </div>
            {sessions.map(s => (
              <label key={s.table} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '0.78rem', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={!!selectedTables[s.table]}
                  onChange={e => setSelectedTables(prev => ({ ...prev, [s.table]: e.target.checked }))}
                />
                <span style={{ fontSize: '0.78rem', fontWeight: 500 }}>
                  {s.name || s.sessionId || s.table}
                </span>
                <span style={{ color: '#666' }}>
                  {Number(s.count || 0).toLocaleString()} 条
                  {s.minTime > 0 && s.maxTime > 0 ? ` · ${dateToInput(s.minTime)} ~ ${dateToInput(s.maxTime)}` : ''}
                </span>
                {s.name && s.name !== s.table && (
                  <span style={{ color: '#999', fontSize: '0.68rem', fontFamily: 'monospace' }}>{s.table}</span>
                )}
              </label>
            ))}
          </div>
        )}
        {sessions && sessions.length === 0 && (
          <span style={{ fontSize: '0.8rem', color: '#666' }}>{t('localBridge.sessionsEmpty')}</span>
        )}

        {/* date range */}
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <label style={{ fontSize: '0.78rem', color: '#666' }}>{t('localBridge.exportFromLabel')}</label>
          <input
            type="date"
            value={fromDate}
            onChange={e => setFromDate(e.target.value)}
            style={{ ...styles.select, width: 'auto', padding: '4px 8px', fontSize: '0.78rem' }}
          />
          <label style={{ fontSize: '0.78rem', color: '#666' }}>{t('localBridge.exportToLabel')}</label>
          <input
            type="date"
            value={toDate}
            onChange={e => setToDate(e.target.value)}
            style={{ ...styles.select, width: 'auto', padding: '4px 8px', fontSize: '0.78rem' }}
          />
          <span style={{ fontSize: '0.72rem', color: '#666' }}>{t('localBridge.exportDateHint')}</span>
        </div>

        {/* close-WeChat hint: newest rows live in -wal files the raw reader
            cannot see while WeChat holds them */}
        <div style={{ fontSize: '0.75rem', color: '#b45309', background: 'rgba(180,83,9,0.08)', border: '1px solid rgba(180,83,9,0.18)', borderRadius: '8px', padding: '7px 10px', maxWidth: '780px' }}>
          {t('localBridge.exportCloseWeChatHint')}
        </div>

        {/* export */}
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <button
            onClick={handleExport}
            disabled={busy.export || !dbKey.trim() || !dbPath.trim()}
            style={styles.buttonSuccess}
          >
            {busy.export ? t('localBridge.exportMediaRunning') : t('localBridge.exportMediaButton')}
          </button>
          {!dbKey.trim() && (
            <span style={{ fontSize: '0.8rem', color: '#666' }}>{t('localBridge.exportNeedsValid')}</span>
          )}
          {!mediaReady && dbKey.trim() && (
            <span style={{ fontSize: '0.8rem', color: '#666' }}>{t('localBridge.mediaNotReady')}</span>
          )}
          {!sessions && !sessionError && dbKey.trim() && (
            <span style={{ fontSize: '0.8rem', color: '#666' }}>{t('localBridge.exportNoSessionsHint')}</span>
          )}
          {exportDone && (
            <span style={{ fontSize: '0.85rem', color: '#059669', maxWidth: '640px' }}>
              ✓ {t('localBridge.exportMediaSummary', { file: exportDone.fileName, count: exportDone.count, media: exportDone.media, failed: exportDone.failed })}
              {exportDone.shards > 0 && (
                <span style={{ color: '#666' }}> — {t('localBridge.exportShardsMerged', { shards: exportDone.shards })}</span>
              )}
              {exportDone.fromDate || exportDone.toDate
                ? <span style={{ color: '#666' }}> ({t('localBridge.exportDateRangeHint', { from: exportDone.fromDate || '…', to: exportDone.toDate || '…' })})</span>
                : <span style={{ color: '#666' }}> — {t('localBridge.exportDateRangeAll')}</span>}
              {exportDone.media === 0 && exportDone.reason && (
                <span style={{ color: '#b45309' }}> — {t('localBridge.exportMediaNoImages', { reason: exportDone.reason })}</span>
              )}
            </span>
          )}
          {exportWarnings.length > 0 && (
            <div style={{ fontSize: '0.78rem', color: '#b45309', background: 'rgba(180,83,9,0.08)', border: '1px solid rgba(180,83,9,0.25)', borderRadius: '8px', padding: '8px 10px', maxWidth: '760px' }}>
              <strong>⚠️ {t('localBridge.exportWarningsTitle')}</strong>
              {exportWarnings.map((msg, i) => <div key={i}>{msg}</div>)}
            </div>
          )}
        </div>
        {exportPath && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', background: 'var(--bg-subtle, #f7f7f7)', borderRadius: '8px', padding: '10px 12px' }}>
            <span style={{ fontSize: '0.82rem', color: '#111' }}>
              <strong>{t('localBridge.exportSavedTo')}</strong>{' '}
              <code style={{ fontFamily: 'monospace', fontSize: '0.78rem', wordBreak: 'break-all' }}>{exportPath}</code>
              {exportSize > 0 && <span style={{ color: '#666' }}> ({fmtBytes(exportSize)})</span>}
            </span>
            <span style={{ fontSize: '0.8rem' }}>
              {t('localBridge.exportDiskHint')}{' '}
              <a
                href={`${baseUrl.replace(/\/+$/, '')}/api/export/download`}
                style={{ color: '#2563eb', fontWeight: '600' }}
              >
                {t('localBridge.exportDownloadLink')}
              </a>
            </span>
          </div>
        )}
        <span style={{ fontSize: '0.75rem', color: '#666', maxWidth: '780px' }}>{t('localBridge.includeMediaNote')}</span>
      </div>

      {/* bridge logs */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', borderTop: '1px solid var(--border, #eee)', paddingTop: '14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', flexWrap: 'wrap' }}>
          <h4 style={{ margin: 0, fontSize: '0.95rem', color: 'var(--text-primary, #111)' }}>
            {t('localBridge.logsTitle')}
            <span
              style={{
                marginLeft: '8px',
                fontSize: '0.7rem',
                fontWeight: '600',
                padding: '1px 7px',
                borderRadius: '10px',
                border: '1px solid',
                color: health?.logLevel === 'debug' ? '#059669' : '#64748b',
                borderColor: health?.logLevel === 'debug' ? '#a7f3d0' : '#d1d5db',
                background: health?.logLevel === 'debug' ? '#ecfdf5' : '#f9fafb'
              }}
            >
              {t('localBridge.logsLevelChip', { level: health?.logLevel || 'info' })}
            </span>
          </h4>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
            <label style={{ fontSize: '0.72rem', color: '#666' }}>{t('localBridge.logsLevelLabel')}</label>
            <select
              value={logLevel}
              onChange={e => setLogLevel(e.target.value)}
              style={{ ...styles.select, width: 'auto', padding: '3px 8px', fontSize: '0.72rem' }}
            >
              <option value="all">{t('localBridge.logsLevelAll')}</option>
              <option value="info">{t('localBridge.logsLevelInfo')}</option>
              <option value="warn">{t('localBridge.logsLevelWarn')}</option>
              <option value="error">{t('localBridge.logsLevelError')}</option>
            </select>
            <button onClick={() => setLogPaused(p => !p)} style={styles.buttonSecondary}>
              {logPaused ? t('localBridge.logsResume') : t('localBridge.logsPause')}
            </button>
          </div>
        </div>
        {logStreamError && (
          <span style={{ fontSize: '0.78rem', color: '#b45309', maxWidth: '780px' }}>
            ⚠️ {t('localBridge.logsStreamError', { error: logStreamError })}
          </span>
        )}
        <span style={{ fontSize: '0.72rem', color: '#666', maxWidth: '780px' }}>{t('localBridge.logsDebugHint')}</span>
        <pre
          style={{
            margin: 0,
            maxHeight: '260px',
            overflow: 'auto',
            background: '#0f172a',
            color: '#e2e8f0',
            borderRadius: '8px',
            padding: '10px 12px',
            fontSize: '0.75rem',
            lineHeight: '1.5',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all'
          }}
        >
          {logs.filter(l => logLevel === 'all' || (LOG_RANK[l.level] ?? 0) >= LOG_RANK[logLevel]).length === 0 && (
            <span style={{ color: '#64748b' }}>
              {logs.length === 0 ? t('localBridge.logsEmpty') : t('localBridge.logsEmptyFiltered')}
            </span>
          )}
          {logs
            .filter(l => logLevel === 'all' || (LOG_RANK[l.level] ?? 0) >= LOG_RANK[logLevel])
            .map(l => (
              <div key={l.seq} style={{ color: LOG_LEVEL_COLOR[l.level] || '#e2e8f0' }}>
                <span style={{ color: '#64748b' }}>{l.ts}</span>{' '}
                <span style={{ color: '#94a3b8', fontWeight: '600' }}>[{l.level}]</span> {l.msg}
              </div>
            ))}
        </pre>

        {/* server-side log mirror (--log-webhook -> backend bridge-logs.log) */}
        <div style={{ borderTop: '1px dashed var(--border, #ddd)', paddingTop: '10px', marginTop: '4px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap' }}>
            <button
              onClick={() => setServerLogVisible(v => !v)}
              style={{ ...styles.buttonSecondary, fontSize: '0.78rem', padding: '3px 10px' }}
            >
              {serverLogVisible ? '▾' : '▸'} {t('localBridge.serverLogsTitle')}
              <span style={{ marginLeft: '6px', color: webhookState === 'on' ? '#15803d' : webhookState === 'error' ? '#b45309' : '#666', fontSize: '0.7rem' }}>
                {webhookState === 'on' ? '●' : webhookState === 'error' ? '⚠' : webhookState === 'off' ? '◎' : '…'}
              </span>
            </button>
            {serverLogVisible && (
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
                <button onClick={copyServerLogs} style={{ ...styles.buttonSecondary, fontSize: '0.72rem', padding: '2px 8px' }}>
                  {serverLogCopied ? t('localBridge.copied') : t('localBridge.copy')}
                </button>
                <span style={{ fontSize: '0.72rem', color: '#666' }}>
                  {serverLogs.length > 0 ? t('localBridge.serverLogsCount', { n: serverLogs.length }) : ''}
                </span>
              </div>
            )}
          </div>
          {serverLogVisible && (
            <>
              {webhookState === 'error' && (
                <span style={{ fontSize: '0.72rem', color: '#b45309', display: 'block', marginTop: '6px' }}>
                  {t('localBridge.serverLogsWebhookError')}
                </span>
              )}
              {webhookState === 'off' && (
                <span style={{ fontSize: '0.72rem', color: '#b45309', display: 'block', marginTop: '6px' }}>
                  {t('localBridge.serverLogsWebhookOff')}
                </span>
              )}
              {serverLogError && (
                <span style={{ fontSize: '0.75rem', color: '#b45309', display: 'block', marginTop: '6px' }}>
                  ⚠️ {t('localBridge.serverLogsError', { error: serverLogError })}
                </span>
              )}
              <span style={{ fontSize: '0.72rem', color: '#666', display: 'block', margin: '6px 0' }}>
                {t('localBridge.serverLogsHint')}
              </span>
              <pre
                style={{
                  margin: 0,
                  maxHeight: '220px',
                  overflow: 'auto',
                  background: '#0f172a',
                  color: '#e2e8f0',
                  borderRadius: '8px',
                  padding: '10px 12px',
                  fontSize: '0.75rem',
                  lineHeight: '1.5',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all'
                }}
              >
                {serverLogs.length === 0 && (
                  <span style={{ color: '#64748b' }}>{t('localBridge.serverLogsEmpty')}</span>
                )}
                {serverLogs.map(l => (
                  <div key={l.seq} style={{ color: LOG_LEVEL_COLOR[l.level] || '#e2e8f0' }}>
                    <span style={{ color: '#64748b' }}>{l.ts}</span>{' '}
                    <span style={{ color: '#94a3b8', fontWeight: '600' }}>[{l.level}]</span> {l.msg}
                  </div>
                ))}
              </pre>
            </>
          )}
        </div>
      </div>
    </div>
  );
}