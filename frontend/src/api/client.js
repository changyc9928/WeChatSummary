import { API_BASE_URL } from '../config';

export function apiRequest(path, { userUuid, ...options } = {}) {
  const headers = { ...(options.headers || {}) };
  if (userUuid) headers['X-User-Id'] = userUuid;

  return fetch(`${API_BASE_URL}${path}`, { ...options, headers });
}