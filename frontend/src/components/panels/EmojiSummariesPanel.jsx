import React, { useState, useEffect } from 'react';
import { styles } from '../../styles/dashboardStyles';
import { apiClient } from '../../api/client';
import useLanguage from '../../hooks/useLanguage';

export default function EmojiSummariesPanel({
  uuidInput,
  emojiSummaries,
  loadingEmojis,
  emojiPagination,
  fetchEmojiSummaries,
  selectedEmojiIds,
  setSelectedEmojiIds,
  handleDeleteEmoji,
  handleBatchDeleteEmojis,
  loading,
  errorEmojis,
  currentUser,
  setActiveModalEmoji
}) {
  const { t } = useLanguage();
  const [emojiObjectUrls, setEmojiObjectUrls] = useState({});

  // Fetch emoji image bytes with X-User-Id header and create local Object URLs
  useEffect(() => {
    const objectUrls = {};
    let isMounted = true;

    const loadEmojis = async () => {
      if (!emojiSummaries || emojiSummaries.length === 0 || !currentUser) return;

      for (const item of emojiSummaries) {
        try {
          const blob = await apiClient.preprocess.getEmojiFileById({
            xUserId: currentUser.uuid,
            id: item.id
          });
          if (isMounted) {
            objectUrls[item.id] = URL.createObjectURL(blob);
            setEmojiObjectUrls({ ...objectUrls });
          }
        } catch (err) {
          console.error(`Failed to load emoji for id ${item.id}`, err);
        }
      }
    };

    loadEmojis();

    return () => {
      isMounted = false;
      Object.values(objectUrls).forEach(url => URL.revokeObjectURL(url));
    };
  }, [emojiSummaries, currentUser]);

  const toggleSelectAll = (e) => {
    if (e.target.checked) {
      setSelectedEmojiIds(emojiSummaries.map(item => item.id));
    } else {
      setSelectedEmojiIds([]);
    }
  };

  const toggleSelectOne = (id) => {
    setSelectedEmojiIds(prev => prev.includes(id) ? prev.filter(i => i !== id) : [...prev, id]);
  };

  if (!uuidInput) return null;

  return (
    <div style={{ ...styles.card, gridColumn: '1 / -1' }}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>{t('emojis.panelTitle')}</h3>
        {selectedEmojiIds.length > 0 && (
          <button onClick={handleBatchDeleteEmojis} disabled={loading.batchDeleteEmojis} style={styles.buttonDangerSmall}>
            {loading.batchDeleteEmojis ? t('emojis.deleting') : t('emojis.deleteSelected', { count: selectedEmojiIds.length })}
          </button>
        )}
      </div>

      {errorEmojis && <div style={styles.errorText}>⚠️ {errorEmojis}</div>}

      {loadingEmojis ? (
        <div style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>{t('emojis.loading')}</div>
      ) : emojiSummaries.length === 0 ? (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{t('emojis.none')}</div>
      ) : (
        <>
          <div style={styles.tableWrapper}>
            <table style={styles.table}>
              <thead>
                <tr style={styles.tr}>
                  <th style={styles.th}><input type="checkbox" onChange={toggleSelectAll} checked={selectedEmojiIds.length === emojiSummaries.length && emojiSummaries.length > 0} /></th>
                  <th style={styles.th}>{t('emojis.thumbnail')}</th>
                  <th style={styles.th}>{t('emojis.aiSummary')}</th>
                  <th style={styles.th}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {emojiSummaries.map((item) => {
                  const objectUrl = emojiObjectUrls[item.id];

                  return (
                    <tr key={item.id} style={styles.tr}>
                      <td style={styles.td}><input type="checkbox" checked={selectedEmojiIds.includes(item.id)} onChange={() => toggleSelectOne(item.id)} /></td>
                      <td style={styles.td}>
                        <div
                          style={{ ...styles.thumbnailContainer, cursor: objectUrl ? 'pointer' : 'default' }}
                          onClick={() => objectUrl && setActiveModalEmoji({ url: objectUrl, summary: item.summary })}
                        >
                          {objectUrl ? (
                            <img src={objectUrl} alt="emoji" style={styles.thumbnail} />
                          ) : (
                            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textAlign: 'center' }}>{t('common.loading')}</div>
                          )}
                        </div>
                      </td>
                      <td style={styles.td}>
                        <div style={styles.summaryText}>{item.summary || <span style={styles.emptySummaryBadge}>{t('common.noSummary')}</span>}</div>
                      </td>
                      <td style={styles.td}>
                        <button onClick={() => handleDeleteEmoji(item.id)} disabled={loading.deleteEmoji} style={styles.buttonDangerSmall}>{t('common.delete')}</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div style={styles.paginationContainer}>
            <span style={styles.paginationInfo}>{t('pagination.pageInfo', { page: emojiPagination.page + 1, totalPages: emojiPagination.totalPages || 1, total: emojiPagination.totalElements })}</span>
            <div style={styles.paginationControls}>
              <button disabled={emojiPagination.isFirst} onClick={() => fetchEmojiSummaries(uuidInput, emojiPagination.page - 1, emojiPagination.size)} style={emojiPagination.isFirst ? styles.pageButtonDisabled : styles.pageButton}>{t('pagination.previous')}</button>
              <button disabled={emojiPagination.isLast} onClick={() => fetchEmojiSummaries(uuidInput, emojiPagination.page + 1, emojiPagination.size)} style={emojiPagination.isLast ? styles.pageButtonDisabled : styles.pageButton}>{t('pagination.next')}</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
