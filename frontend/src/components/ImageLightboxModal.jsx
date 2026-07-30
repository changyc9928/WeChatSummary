import React from 'react';
import { styles } from '../styles/dashboardStyles';

export default function ImageLightboxModal({ activeModalImage, setActiveModalImage }) {
  if (!activeModalImage) return null;

  return (
    <div style={styles.modalOverlay} onClick={() => setActiveModalImage(null)}>
      <div style={styles.modalContent} onClick={e => e.stopPropagation()}>
        <button style={styles.modalCloseButton} onClick={() => setActiveModalImage(null)}>✕</button>
        <div style={styles.modalImageWrapper}>
          <img src={activeModalImage.url} alt="Expanded Fullview" style={styles.modalImage} />
        </div>
        <div style={styles.modalFooter}>
          <span style={{ fontSize: '0.85rem', color: '#4b5563', lineHeight: '1.4' }}>
            <strong>Summary:</strong> {activeModalImage.summary || 'No summary available.'}
          </span>
        </div>
      </div>
    </div>
  );
}