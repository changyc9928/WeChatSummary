import React from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';

export default function DatasetSelector({ sessions, uuidInput, setUuidInput, fetchSessions, loadingSessions }) {
  const { t } = useLanguage();

  return (
    <div style={styles.uuidCard}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
        <label style={styles.label}>{t('dataset.activeTarget')}</label>
        <button onClick={fetchSessions} style={styles.refreshButton}>
          {loadingSessions ? t('dataset.refreshing') : t('dataset.refreshList')}
        </button>
      </div>
      <select 
        value={uuidInput} 
        onChange={(e) => setUuidInput(e.target.value)} 
        style={styles.select} 
        disabled={loadingSessions}
      >
        <option value="">{t('dataset.choosePlaceholder')}</option>
        {sessions.map((session) => (
          <option key={session.uuid} value={session.uuid}>
            {session.jsonFilename} | [{t('dataset.uploadedAt', { time: session.uploadedAt })}]
          </option>
        ))}
      </select>
    </div>
  );
}