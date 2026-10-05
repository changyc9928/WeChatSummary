import { useEffect, useState } from 'react';
import { apiClient } from '../api/client';
import { clearAuthToken, getAuthToken, setAuthToken } from '../api/authToken';

const STORAGE_KEY = 'wechat_current_user';

export default function useAuth() {
  const [currentUser, setCurrentUser] = useState(null);

  useEffect(() => {
    const savedUser = localStorage.getItem(STORAGE_KEY);
    if (!savedUser) return;
    // An existing profile without a token is a stale pre-Bearer session; force re-login.
    if (!getAuthToken()) {
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    try {
      setCurrentUser(JSON.parse(savedUser));
    } catch {
      localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  const login = ({ token, username }) => {
    setAuthToken(token);
    // The frontend only keeps the display name; the user UUID stays server-side.
    const user = { username };
    setCurrentUser(user);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
  };

  const logout = () => {
    // Best-effort server-side revocation; the local session is cleared regardless.
    apiClient.auth.logout().catch(() => {});
    clearAuthToken();
    setCurrentUser(null);
    localStorage.removeItem(STORAGE_KEY);
  };

  return { currentUser, login, logout };
}
