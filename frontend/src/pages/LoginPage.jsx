import React, { useState } from 'react';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import useLanguage from '../hooks/useLanguage';
import { apiClient } from '../api/client';

export default function LoginPage({ onLoginSuccess, theme, onToggleTheme }) {
  const { t } = useLanguage();
  const [authMode, setAuthMode] = useState('login'); // 'login' or 'register'
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState(null);

  const handleAuthSubmit = async (e) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      setAuthError(t('login.fillBoth'));
      return;
    }

    setAuthLoading(true);
    setAuthError(null);

    const credentials = { username, password };

    try {
      const data = authMode === 'login'
        ? await apiClient.auth.login({ authRequest: credentials })
        : await apiClient.auth.register({ authRequest: credentials });

      onLoginSuccess({ uuid: data?.data?.uuid, username });
    } catch (err) {
      setPassword('');
      setAuthError(err.message);
    } finally {
      setAuthLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'var(--bg)', color: 'var(--text-secondary)', fontFamily: 'system-ui, sans-serif', transition: 'background-color 0.2s ease' }}>
      <div style={{ position: 'absolute', top: '16px', right: '16px', display: 'flex', gap: '8px' }}>
        <LanguageToggle />
        <ThemeToggle theme={theme} onToggle={onToggleTheme} />
      </div>
      <div style={{ background: 'var(--bg-card)', padding: '30px', borderRadius: '12px', boxShadow: '0 4px 12px rgba(0,0,0,0.05)', width: '100%', maxWidth: '400px' }}>
        <h2 style={{ textAlign: 'center', marginBottom: '20px', color: 'var(--text-primary)' }}>
          {authMode === 'login' ? t('login.userLogin') : t('login.register')}
        </h2>

        {authError && (
          <div style={{ backgroundColor: '#fef2f2', color: '#dc2626', padding: '10px', borderRadius: '6px', marginBottom: '15px', fontSize: '0.85rem', border: '1px solid #fee2e2' }}>
            {authError}
          </div>
        )}

        <form onSubmit={handleAuthSubmit}>
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px', fontWeight: '600', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>{t('login.username')}</label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder={t('login.usernamePlaceholder')}
              style={{ width: '100%', padding: '10px', boxSizing: 'border-box', border: '1px solid var(--border-strong)', borderRadius: '8px', fontSize: '0.95rem', backgroundColor: 'var(--bg-card)', color: 'var(--text-primary)' }}
            />
          </div>

          <div style={{ marginBottom: '20px' }}>
            <label style={{ display: 'block', marginBottom: '5px', fontWeight: '600', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>{t('login.password')}</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder={t('login.passwordPlaceholder')}
              style={{ width: '100%', padding: '10px', boxSizing: 'border-box', border: '1px solid var(--border-strong)', borderRadius: '8px', fontSize: '0.95rem', backgroundColor: 'var(--bg-card)', color: 'var(--text-primary)' }}
            />
          </div>

          <button
            type="submit"
            disabled={authLoading}
            style={{ width: '100%', padding: '10px', backgroundColor: '#2563eb', color: '#fff', border: 'none', borderRadius: '8px', fontWeight: '600', cursor: authLoading ? 'not-allowed' : 'pointer', fontSize: '0.95rem', opacity: authLoading ? 0.7 : 1 }}
          >
            {authLoading ? t('login.processing') : (authMode === 'login' ? t('login.submitLogin') : t('login.submitRegister'))}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: '15px' }}>
          {authMode === 'login' ? (
            <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
              {t('login.noAccount')}{' '}
              <button
                type="button"
                onClick={() => { setAuthMode('register'); setAuthError(null); setPassword(''); }}
                style={{ background: 'none', border: 'none', color: 'var(--accent)', cursor: 'pointer', textDecoration: 'underline', padding: 0, fontWeight: '600' }}
              >
                {t('login.registerHere')}
              </button>
            </p>
          ) : (
            <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
              {t('login.hasAccount')}{' '}
              <button
                type="button"
                onClick={() => { setAuthMode('login'); setAuthError(null); setPassword(''); }}
                style={{ background: 'none', border: 'none', color: 'var(--accent)', cursor: 'pointer', textDecoration: 'underline', padding: 0, fontWeight: '600' }}
              >
                {t('login.loginHere')}
              </button>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}