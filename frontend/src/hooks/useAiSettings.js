import { useCallback, useState } from 'react';
import { aiSettingsApi } from '../api/settings';

const SECRET_FIELDS = ['chatApiKey', 'imageApiKey', 'videoApiKey', 'transcriptionApiKey'];

/**
 * Loads/saves the server-wide AI provider settings.
 * Secret inputs start empty (server only returns masked values); untouched
 * secrets are omitted from the save payload so they keep their current value.
 */
export default function useAiSettings() {
  const [server, setServer] = useState(null);
  const [draft, setDraft] = useState({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await aiSettingsApi.get();
      setServer(data || {});
      setDraft({});
      setNotice('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const setField = useCallback((name, value) => {
    setDraft(prev => ({ ...prev, [name]: value }));
    setNotice('');
  }, []);

  // Effective value shown in a non-secret input: draft wins, then server value.
  const valueFor = useCallback((name) => {
    if (draft[name] !== undefined) return draft[name];
    return server?.[name] ?? '';
  }, [draft, server]);

  const save = useCallback(async () => {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const payload = {};
      Object.entries(draft).forEach(([k, v]) => {
        const s = String(v ?? '').trim();
        if (SECRET_FIELDS.includes(k)) {
          // Only send secrets the user actually typed; empty keeps the server value.
          if (s) payload[k] = s;
        } else if (server?.[k] === undefined || s !== String(server[k] ?? '').trim()) {
          payload[k] = s;
        }
      });
      const data = await aiSettingsApi.update(payload);
      setServer(data || {});
      setDraft({});
      return data;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setSaving(false);
    }
  }, [draft, server]);

  const resetAll = useCallback(async () => {
    setResetting(true);
    setError('');
    setNotice('');
    try {
      const data = await aiSettingsApi.reset();
      setServer(data || {});
      setDraft({});
      return data;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setResetting(false);
    }
  }, []);

  return {
    server, draft, loading, saving, resetting, error, notice,
    setError, setNotice, load, setField, valueFor, save, resetAll
  };
}
