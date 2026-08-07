import React from 'react';
import { styles } from '../../styles/dashboardStyles';

export default function StepUpload({ file, setFile, handleUpload, loadingUpload, errorUpload }) {
  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>Step 1: Upload Source Data</h3>
        <span style={styles.lockBadge}>Ready</span>
      </div>
      <form onSubmit={handleUpload} style={styles.form}>
        <input 
          type="file" 
          onChange={(e) => setFile(e.target.files[0])} 
          style={styles.fileInput} 
        />
        <button type="submit" disabled={loadingUpload} style={styles.button}>
          {loadingUpload ? 'Uploading & Creating Session...' : 'Upload File'}
        </button>
      </form>
      {errorUpload && <div style={styles.errorText}>⚠️ {errorUpload}</div>}
    </div>
  );
}