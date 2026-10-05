const TOKEN_KEY = 'wechat_auth_token';

/** Returns the stored Bearer token, or '' when not logged in. */
export function getAuthToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || '';
  } catch {
    return '';
  }
}

/** Persists (or, with an empty value, clears) the Bearer token. */
export function setAuthToken(token) {
  try {
    if (token) {
      localStorage.setItem(TOKEN_KEY, token);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // storage unavailable (private mode); the in-memory session still works.
  }
}

export function clearAuthToken() {
  setAuthToken('');
}

/**
 * Merges the Authorization header into a headers object. Raw-fetch modules that
 * bypass the generated OpenAPI client must use this so the universal backend
 * interceptor can resolve the user from the Bearer token.
 */
export function withAuthHeaders(headers = {}) {
  const token = getAuthToken();
  return token ? { ...headers, Authorization: `Bearer ${token}` } : { ...headers };
}
