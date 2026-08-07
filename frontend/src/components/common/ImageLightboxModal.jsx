import React, { useEffect } from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';

export default function ImageLightboxModal({ activeModalImage, setActiveModalImage }) {
  const { t } = useLanguage();
  useEffect(() => {
    if (!activeModalImage) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') setActiveModalImage(null);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [activeModalImage, setActiveModalImage]);

  if (!activeModalImage) return null;

  return (
    <div style={styles.modalOverlay} onClick={() => setActiveModalImage(null)}>
      <div style={styles.modalContent} onClick={e => e.stopPropagation()}>
        <button style={styles.modalCloseButton} onClick={() => setActiveModalImage(null)}>✕</button>
        <div style={styles.modalImageWrapper}>
          <img src={activeModalImage.url} alt="Expanded Fullview" style={styles.modalImage} />
        </div>
        <div style={styles.modalFooter}>
          <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: '1.4' }}>
            <strong>{t('modal.summary')}</strong> {activeModalImage.summary || t('modal.noSummary')}
          </span>
        </div>
      </div>
    </div>
  );
}