import { API_BASE_URL } from '../config';

/**
 * Server-wide AI provider settings (keys, endpoints, models).
 * Secrets are only ever returned masked; blank fields keep their current value.
 */

function base() {
  return String(API_BASE_URL || '').replace(/\/+$/, '');
}

async function settingsRequest({ method = 'GET', body } = {}) {
  let res;
  try {
    res = await fetch(`${base()}/api/settings/ai`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined
    });
  } catch (err) {
    throw new Error(`Unable to reach the server. Please check your network connection and try again. (${err.message})`);
  }
  let payload = null;
  try {
    payload = await res.json();
  } catch {
    // non-JSON body (unexpected)
  }
  if (!res.ok) {
    throw new Error(payload?.message || `Request failed with status ${res.status}`);
  }
  return payload?.data ?? null;
}

export const aiSettingsApi = {
  get: () => settingsRequest({ method: 'GET' }),
  update: (fields) => settingsRequest({ method: 'PUT', body: fields || {} }),
  reset: () => settingsRequest({ method: 'DELETE' })
};
