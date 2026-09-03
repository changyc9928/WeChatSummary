import React, { useState } from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';

export default function DatasetSelector({ sessions, uuidInput, setUuidInput, fetchSessions, loadingSessions, deleteSession }) {
  const { t } = useLanguage();
  const [deletingId, setDeletingId] = useState(null);

  const handleDelete = async (e, sessionUuid) => {
    e.stopPropagation();
    if (!confirm(t('dataset.confirmDelete'))) return;
    setDeletingId(sessionUuid);
    try {
      await deleteSession(sessionUuid);
      if (uuidInput === sessionUuid) {
        setUuidInput('');
      }
    } catch (err) {
      // error is surfaced via useSessions.error
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div style={styles.uuidCard}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
        <label style={styles.label}>{t('dataset.activeTarget')}</label>
        <button onClick={fetchSessions} style={styles.refreshButton}>
          {loadingSessions ? t('dataset.refreshing') : t('dataset.refreshList')}
        </button>
      </div>
      <div
        style={{
          border: `1px solid var(--border-strong)`,
          borderRadius: '8px',
          maxHeight: '200px',
          overflowY: 'auto',
          backgroundColor: 'var(--bg-card)',
        }}
      >
        <div
          onClick={() => setUuidInput('')}
          style={{
            padding: '8px 12px',
            cursor: 'pointer',
            fontSize: '0.95rem',
            color: uuidInput === '' ? 'var(--accent)' : 'var(--text-muted)',
            fontWeight: uuidInput === '' ? '600' : '400',
            backgroundColor: uuidInput === '' ? 'var(--bg-subtle)' : 'transparent',
            borderBottom: '1px solid var(--border)',
            transition: 'background-color 0.15s',
          }}
        >
          {t('dataset.choosePlaceholder')}
        </div>
        {sessions.map((session) => {
          const isSelected = uuidInput === session.uuid;
          const isDeleting = deletingId === session.uuid;
          return (
            <div
              key={session.uuid}
              onClick={() => setUuidInput(session.uuid)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '8px 12px',
                cursor: 'pointer',
                fontSize: '0.95rem',
                color: isSelected ? 'var(--accent)' : 'var(--text-primary)',
                fontWeight: isSelected ? '600' : '400',
                backgroundColor: isSelected ? 'var(--bg-subtle)' : 'transparent',
                borderBottom: '1px solid var(--border)',
                transition: 'background-color 0.15s',
              }}
              onMouseEnter={(e) => { if (!isSelected) e.currentTarget.style.backgroundColor = 'var(--bg-subtle)'; }}
              onMouseLeave={(e) => { if (!isSelected) e.currentTarget.style.backgroundColor = 'transparent'; }}
            >
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {session.jsonFilename} | [{t('dataset.uploadedAt', { time: session.uploadedAt })}]
              </span>
              <button
                onClick={(e) => handleDelete(e, session.uuid)}
                disabled={isDeleting}
                style={{
                  marginLeft: '8px',
                  backgroundColor: isDeleting ? '#9ca3af' : '#dc2626',
                  color: '#fff',
                  border: 'none',
                  padding: '3px 8px',
                  borderRadius: '4px',
                  fontSize: '0.72rem',
                  fontWeight: '600',
                  cursor: isDeleting ? 'not-allowed' : 'pointer',
                  flexShrink: 0,
                  opacity: isDeleting ? 0.7 : 1,
                  transition: 'background-color 0.15s',
                }}
                onMouseEnter={(e) => { if (!isDeleting) e.currentTarget.style.backgroundColor = '#b91c1c'; }}
                onMouseLeave={(e) => { if (!isDeleting) e.currentTarget.style.backgroundColor = '#dc2626'; }}
              >
                {isDeleting ? '...' : t('dataset.delete')}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
