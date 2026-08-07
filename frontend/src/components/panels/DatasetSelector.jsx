import React from 'react';
import { styles } from '../../styles/dashboardStyles';

export default function DatasetSelector({ sessions, uuidInput, setUuidInput, fetchSessions, loadingSessions }) {
  return (
    <div style={styles.uuidCard}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
        <label style={styles.label}>Active Analysis Target (UUID):</label>
        <button onClick={fetchSessions} style={styles.refreshButton}>
          {loadingSessions ? 'Refreshing...' : '🔄 Refresh List'}
        </button>
      </div>
      <select 
        value={uuidInput} 
        onChange={(e) => setUuidInput(e.target.value)} 
        style={styles.select} 
        disabled={loadingSessions}
      >
        <option value="">-- Choose an Existing Uploaded Dataset --</option>
        {sessions.map((session) => (
          <option key={session.uuid} value={session.uuid}>
            {session.jsonFilename} | [Uploaded: {session.uploadedAt}]
          </option>
        ))}
      </select>
    </div>
  );
}