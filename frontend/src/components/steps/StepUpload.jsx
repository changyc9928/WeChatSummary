import React from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';

export default function StepUpload({ file, setFile, handleUpload, loadingUpload, errorUpload }) {
  const { t } = useLanguage();

  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>{t('upload.title')}</h3>
        <span style={styles.lockBadge}>{t('upload.ready')}</span>
      </div>
      <form onSubmit={handleUpload} style={styles.form}>
        <input 
          type="file" 
          onChange={(e) => setFile(e.target.files[0])} 
          style={styles.fileInput} 
        />
        <button type="submit" disabled={loadingUpload} style={styles.button}>
          {loadingUpload ? t('upload.uploading') : t('upload.uploadFile')}
        </button>
      </form>
      {errorUpload && <div style={styles.errorText}>⚠️ {errorUpload}</div>}
    </div>
  );
}