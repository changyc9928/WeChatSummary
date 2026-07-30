import React, { useState } from 'react';

export default function LoginForm({ onLoginSuccess, apiUrl }) {
  const [authMode, setAuthMode] = useState('login'); // 'login' or 'register'
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState(null);

  const handleAuthSubmit = async (e) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      setAuthError('Please fill in both username and password.');
      return;
    }

    setAuthLoading(true);
    setAuthError(null);

    const endpoint = authMode === 'login' ? '/api/auth/login' : '/api/auth/register';

    try {
      const response = await fetch(`${apiUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });

      if (!response.ok) {
        let errorMsg = `${authMode === 'login' ? 'Login' : 'Registration'} failed.`;
        try {
          const errData = await response.json();
          if (errData && errData.message) {
            errorMsg = errData.message;
          }
        } catch (parseError) {
          const errText = await response.text();
          if (errText) errorMsg = errText;
        }
        
        setPassword('');
        throw new Error(errorMsg);
      }

      const data = await response.json();
      const userData = { uuid: data.uuid, username };
      
      onLoginSuccess(userData);
    } catch (err) {
      setAuthError(err.message);
    } finally {
      setAuthLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#f9fafb', fontFamily: 'system-ui, sans-serif' }}>
      <div style={{ background: '#fff', padding: '30px', borderRadius: '12px', boxShadow: '0 4px 12px rgba(0,0,0,0.05)', width: '100%', maxWidth: '400px' }}>
        <h2 style={{ textAlign: 'center', marginBottom: '20px', color: '#111827' }}>
          {authMode === 'login' ? 'User Login' : 'Register New Account'}
        </h2>

        {authError && (
          <div style={{ backgroundColor: '#fef2f2', color: '#dc2626', padding: '10px', borderRadius: '6px', marginBottom: '15px', fontSize: '0.85rem', border: '1px solid #fee2e2' }}>
            {authError}
          </div>
        )}

        <form onSubmit={handleAuthSubmit}>
          <div style={{ marginBottom: '15px' }}>
            <label style={{ display: 'block', marginBottom: '5px', fontWeight: '600', fontSize: '0.9rem', color: '#374151' }}>Username</label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder="Enter your username"
              style={{ width: '100%', padding: '10px', boxSizing: 'border-box', border: '1px solid #d1d5db', borderRadius: '8px', fontSize: '0.95rem' }}
            />
          </div>

          <div style={{ marginBottom: '20px' }}>
            <label style={{ display: 'block', marginBottom: '5px', fontWeight: '600', fontSize: '0.9rem', color: '#374151' }}>Password</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="Enter your password"
              style={{ width: '100%', padding: '10px', boxSizing: 'border-box', border: '1px solid #d1d5db', borderRadius: '8px', fontSize: '0.95rem' }}
            />
          </div>

          <button
            type="submit"
            disabled={authLoading}
            style={{ width: '100%', padding: '10px', backgroundColor: '#2563eb', color: '#fff', border: 'none', borderRadius: '8px', fontWeight: '600', cursor: authLoading ? 'not-allowed' : 'pointer', fontSize: '0.95rem', opacity: authLoading ? 0.7 : 1 }}
          >
            {authLoading ? 'Processing...' : (authMode === 'login' ? 'Login' : 'Register')}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: '15px' }}>
          {authMode === 'login' ? (
            <p style={{ fontSize: '0.85rem', color: '#6b7280' }}>
              Don't have an account?{' '}
              <button
                type="button"
                onClick={() => { setAuthMode('register'); setAuthError(null); setPassword(''); }}
                style={{ background: 'none', border: 'none', color: '#2563eb', cursor: 'pointer', textDecoration: 'underline', padding: 0, fontWeight: '600' }}
              >
                Register here
              </button>
            </p>
          ) : (
            <p style={{ fontSize: '0.85rem', color: '#6b7280' }}>
              Already have an account?{' '}
              <button
                type="button"
                onClick={() => { setAuthMode('login'); setAuthError(null); setPassword(''); }}
                style={{ background: 'none', border: 'none', color: '#2563eb', cursor: 'pointer', textDecoration: 'underline', padding: 0, fontWeight: '600' }}
              >
                Login here
              </button>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}