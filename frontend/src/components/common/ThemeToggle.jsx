import React from 'react';

export default function ThemeToggle({ theme, onToggle, style }) {
  const isDark = theme === 'dark';
  return (
    <button
      type="button"
      onClick={onToggle}
      style={{
        backgroundColor: 'transparent',
        border: '1px solid var(--border-strong)',
        color: 'var(--text-secondary)',
        padding: '6px 10px',
        borderRadius: '6px',
        fontSize: '0.78rem',
        fontWeight: '600',
        cursor: 'pointer',
        ...style
      }}
      title={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
    >
      {isDark ? '☀️ Light' : '🌙 Dark'}
    </button>
  );
}