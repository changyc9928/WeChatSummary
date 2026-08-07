import { API_BASE_URL } from '../config';

async function authenticate(endpoint, { username, password }) {
  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });

  if (!response.ok) {
    let errorMsg = `${endpoint.includes('login') ? 'Login' : 'Registration'} failed.`;
    try {
      const errData = await response.json();
      if (errData && errData.message) {
        errorMsg = errData.message;
      }
    } catch (e) {
      const errText = await response.text();
      if (errText) errorMsg = errText;
    }
    throw new Error(errorMsg);
  }

  const data = await response.json();
  return { uuid: data.uuid, username };
}

export function login({ username, password }) {
  return authenticate('/api/auth/login', { username, password });
}

export function register({ username, password }) {
  return authenticate('/api/auth/register', { username, password });
}