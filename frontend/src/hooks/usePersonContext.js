import { useCallback, useEffect, useState } from 'react';
import { API_BASE_URL } from '../config';
import { fromLocalInputValue } from '../utils/time';

const EMPTY_CONTEXT = { people: [], relationships: [] };

function normalizeContext(raw) {
  const people = Array.isArray(raw?.people) ? raw.people
    .filter(p => p && typeof p.name === 'string' && p.name.trim())
    .map((p, i) => ({
      id: (p.id && String(p.id).trim()) || `p${i + 1}`,
      name: String(p.name).trim(),
      aliases: Array.isArray(p.aliases) ? p.aliases.map(a => String(a).trim()).filter(a => a && a !== String(p.name).trim()) : []
    })) : [];
  const validIds = new Set(people.map(p => p.id));
  const relationships = Array.isArray(raw?.relationships) ? raw.relationships
    .filter(r => r && validIds.has(r.from) && validIds.has(r.to) && r.from !== r.to && typeof r.relationship === 'string' && r.relationship.trim())
    .map(r => ({ from: r.from, to: r.to, relationship: String(r.relationship).trim() })) : [];
  return { people, relationships };
}

async function requestJson(url, { method = 'GET', userId, body } = {}) {
  const res = await fetch(url, {
    method,
    headers: {
      'X-User-Id': userId,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
    },
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Request failed with status ${res.status}`);
  }
  const json = await res.json().catch(() => null);
  return json?.data ?? json;
}

/**
 * Step 3 lightweight person-context state.
 * Backend stores it as a per-task sidecar file; the summary pipeline loads it
 * automatically, so confirming here is enough — no inline payload needed.
 */
export default function usePersonContext({ uuidInput, currentUser, selectedStartTime, selectedEndTime }) {
  const [context, setContext] = useState(EMPTY_CONTEXT);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [extracting, setExtracting] = useState(false);
  const [error, setError] = useState(null);
  const [confirmed, setConfirmed] = useState(false);

  const userId = currentUser?.uuid;

  const buildWindowPayload = useCallback(() => {
    const payload = {};
    if (selectedStartTime) payload.startTime = fromLocalInputValue(selectedStartTime);
    if (selectedEndTime) payload.endTime = fromLocalInputValue(selectedEndTime);
    return payload;
  }, [selectedStartTime, selectedEndTime]);

  const fetchContext = useCallback(async () => {
    if (!uuidInput || !userId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await requestJson(`${API_BASE_URL}/api/summary/person-context/${uuidInput}`, { userId });
      setContext(normalizeContext(data));
      setConfirmed(false);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [uuidInput, userId]);

  const extractContext = useCallback(async () => {
    if (!uuidInput || !userId) return null;
    setExtracting(true);
    setError(null);
    try {
      const data = await requestJson(`${API_BASE_URL}/api/summary/person-context/${uuidInput}`, {
        method: 'POST',
        userId,
        body: buildWindowPayload()
      });
      const normalized = normalizeContext(data);
      setContext(normalized);
      setConfirmed(false);
      return normalized;
    } catch (err) {
      // Extraction failure must not block the original summary flow.
      setError(err.message);
      return null;
    } finally {
      setExtracting(false);
    }
  }, [uuidInput, userId, buildWindowPayload]);

  const saveContext = useCallback(async (next) => {
    if (!uuidInput || !userId) return null;
    const normalized = normalizeContext(next ?? context);
    // Attach the current window so the backend can scope the sidecar to this
    // summary task; a different date range will then regenerate instead of
    // reusing stale people.
    const body = { ...normalized, ...buildWindowPayload() };
    setSaving(true);
    setError(null);
    try {
      const data = await requestJson(`${API_BASE_URL}/api/summary/person-context/${uuidInput}`, {
        method: 'PUT',
        userId,
        body
      });
      const saved = normalizeContext(data);
      setContext(saved);
      setConfirmed(true);
      return saved;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setSaving(false);
    }
  }, [uuidInput, userId, context]);

  useEffect(() => {
    setContext(EMPTY_CONTEXT);
    setConfirmed(false);
    setError(null);
  }, [uuidInput]);

  return {
    context,
    setContext: (next) => { setContext(normalizeContext(next)); setConfirmed(false); },
    loading,
    saving,
    extracting,
    error,
    confirmed,
    fetchContext,
    extractContext,
    saveContext
  };
}

export { normalizeContext };
