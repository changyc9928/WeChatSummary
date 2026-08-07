const v = (name) => `var(--${name})`;

export const styles = {
  container: { maxWidth: '1200px', margin: '0 auto', padding: '40px 20px', fontFamily: 'system-ui, -apple-system, sans-serif', color: v('text-secondary'), backgroundColor: v('bg'), minHeight: '100vh', boxSizing: 'border-box', transition: 'background-color 0.2s ease, color 0.2s ease' },
  header: { textAlign: 'center', marginBottom: '40px' },
  title: { fontSize: '2.2rem', fontWeight: '700', color: v('text-primary'), margin: '0 0 10px 0' },
  subtitle: { color: v('text-muted'), fontSize: '1.05rem', maxWidth: '600px', margin: '0 auto', lineHeight: '1.5' },
  uuidCard: { background: v('bg-card'), padding: '24px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', marginBottom: '30px' },
  label: { display: 'block', fontWeight: '600', color: v('text-primary'), fontSize: '0.95rem' },
  timestampBadge: { fontSize: '0.82rem', color: v('text-muted'), fontWeight: 'normal' },
  refreshButton: { background: 'none', border: 'none', color: v('accent'), cursor: 'pointer', fontSize: '0.85rem', fontWeight: '600' },
  select: { width: '100%', padding: '10px 14px', borderRadius: '8px', border: `1px solid ${v('border-strong')}`, backgroundColor: v('bg-card'), fontSize: '0.95rem', color: v('text-primary') },

  // NEW: Row 1 grid for Step 1 and Step 2 side-by-side
  topRowGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
    gap: '20px',
    marginBottom: '20px'
  },

  // NEW: Full-width container layout for Step 3 underneath
  fullWidthSummaryContainer: {
    width: '100%',
    display: 'flex',
    flexDirection: 'column'
  },

  // (Optional: kept as fallback if grid is used elsewhere)
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '20px' },

  card: { background: v('bg-card'), padding: '20px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', display: 'flex', flexDirection: 'column', gap: '15px' },
  cardHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  cardTitle: { fontSize: '1.1rem', fontWeight: '700', color: v('text-primary'), margin: 0 },
  lockBadge: { fontSize: '0.75rem', backgroundColor: v('bg-subtle'), color: v('text-muted'), padding: '4px 8px', borderRadius: '4px' },
  form: { display: 'flex', flexDirection: 'column', gap: '12px' },
  fileInput: { fontSize: '0.85rem' },
  button: { backgroundColor: v('accent'), color: '#fff', border: 'none', padding: '10px 16px', borderRadius: '8px', fontWeight: '600', cursor: 'pointer', transition: 'background-color 0.2s' },
  buttonSecondary: { backgroundColor: v('bg-card'), color: v('text-secondary'), border: `1px solid ${v('border-strong')}`, padding: '10px 16px', borderRadius: '8px', fontWeight: '600', cursor: 'pointer' },
  buttonDanger: { backgroundColor: '#dc2626', color: '#fff', border: 'none', padding: '10px 16px', borderRadius: '8px', fontWeight: '600', cursor: 'pointer' },
  buttonDangerSmall: { backgroundColor: '#dc2626', color: '#fff', border: 'none', padding: '6px 10px', borderRadius: '6px', fontSize: '0.78rem', fontWeight: '600', cursor: 'pointer' },
  buttonWarningSmall: { backgroundColor: '#d97706', color: '#fff', border: 'none', padding: '6px 10px', borderRadius: '6px', fontSize: '0.78rem', fontWeight: '600', cursor: 'pointer' },
  buttonSuccess: { backgroundColor: '#059669', color: '#fff', border: 'none', padding: '10px 16px', borderRadius: '8px', fontWeight: '600', cursor: 'pointer' },
  buttonWarning: { backgroundColor: '#d97706', color: '#fff', border: 'none', padding: '10px 16px', borderRadius: '8px', fontWeight: '600', cursor: 'pointer' },
  actionButtonGroup: { display: 'flex', flexDirection: 'column', gap: '10px' },
  actionButtonRow: { display: 'flex', gap: '10px' },
  progressSection: { display: 'flex', flexDirection: 'column', gap: '6px' },
  progressLabelRow: { display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem' },
  progressBarBg: { backgroundColor: v('bg-subtle'), borderRadius: '6px', height: '8px', overflow: 'hidden' },
  progressBarFill: { height: '100%', transition: 'width 0.3s ease' },
  errorText: { color: '#dc2626', fontSize: '0.82rem', marginTop: '4px' },
  dbErrorBox: { backgroundColor: '#fef2f2', border: '1px solid #fecaca', padding: '10px', borderRadius: '6px', color: '#991b1b', fontSize: '0.82rem' },
  summaryContainer: { marginTop: '10px', display: 'flex', flexDirection: 'column', gap: '6px', flex: 1 },
  summaryLabel: { fontSize: '0.85rem', fontWeight: '700', color: v('text-primary') },

  // MODIFIED: Expanded large box layout for full page readability
  cleanSummaryOutput: {
    backgroundColor: v('bg-subtle'),
    padding: '16px',
    borderRadius: '8px',
    fontSize: '0.95rem',
    color: v('text-secondary'),
    whiteSpace: 'pre-wrap',
    minHeight: '400px',
    maxHeight: '70vh',
    overflowY: 'auto',
    width: '100%',
    boxSizing: 'border-box',
    lineHeight: '1.6'
  },

  tableWrapper: { overflowX: 'auto', border: `1px solid ${v('border')}`, borderRadius: '8px' },
  table: { width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '0.85rem' },
  th: { backgroundColor: v('bg-subtle'), padding: '12px 16px', borderBottom: `1px solid ${v('border')}`, fontWeight: '600', color: v('text-secondary') },
  tr: { borderBottom: `1px solid ${v('border')}`, transition: 'background-color 0.15s' },
  td: { padding: '12px 16px', verticalAlign: 'top' },
  thumbnailContainer: { width: '60px', height: '60px', borderRadius: '6px', overflow: 'hidden', position: 'relative', cursor: 'pointer', border: `1px solid ${v('border')}`, backgroundColor: v('bg-subtle') },
  thumbnail: { width: '100%', height: '100%', objectFit: 'cover' },
  thumbnailOverlay: { position: 'absolute', inset: 0, backgroundColor: 'rgba(0,0,0,0.4)', color: '#fff', fontSize: '0.65rem', display: 'flex', alignItems: 'center', justifyContent: 'center', opacity: 0, transition: 'opacity 0.2s', fontWeight: 'bold' },
  summaryText: { color: v('text-secondary'), fontSize: '0.83rem', lineHeight: '1.4', maxHeight: '120px', overflowY: 'auto' },
  transcriptBox: { backgroundColor: v('bg-subtle'), padding: '8px 10px', borderRadius: '6px', color: v('text-secondary'), fontSize: '0.82rem', border: `1px solid ${v('border')}`, maxHeight: '120px', overflowY: 'auto', whiteSpace: 'pre-wrap' },
  emptySummaryBadge: { backgroundColor: '#fffbe3', border: '1px dashed #f59e0b', padding: '8px 10px', borderRadius: '6px', color: '#b45309', fontSize: '0.78rem', fontWeight: '500' },
  paginationContainer: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '16px', padding: '4px 0' },
  paginationInfo: { fontSize: '0.85rem', color: v('text-muted') },
  paginationControls: { display: 'flex', alignItems: 'center', gap: '4px' },
  pageButton: { backgroundColor: v('bg-card'), border: `1px solid ${v('border-strong')}`, padding: '5px 10px', borderRadius: '6px', fontSize: '0.8rem', cursor: 'pointer', fontWeight: '500', color: v('text-secondary') },
  pageButtonDisabled: { backgroundColor: v('bg-subtle'), border: `1px solid ${v('border')}`, padding: '5px 10px', borderRadius: '6px', fontSize: '0.8rem', cursor: 'not-allowed', color: v('text-muted') },
  modalOverlay: { position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: '20px' },
  modalContent: { backgroundColor: v('bg-card'), borderRadius: '12px', maxWidth: '800px', width: '100%', overflow: 'hidden', position: 'relative', display: 'flex', flexDirection: 'column', maxHeight: '90vh' },
  modalCloseButton: { position: 'absolute', top: '12px', right: '12px', backgroundColor: 'rgba(0,0,0,0.5)', color: '#fff', border: 'none', width: '32px', height: '32px', borderRadius: '50%', cursor: 'pointer', fontSize: '1rem', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 10 },
  modalImageWrapper: { backgroundColor: '#000', display: 'flex', alignItems: 'center', justifyContent: 'center', maxHeight: '65vh', overflow: 'hidden' },
  modalImage: { maxWidth: '100%', maxHeight: '65vh', objectFit: 'contain' },
  modalFooter: { padding: '16px', backgroundColor: v('bg-card'), borderTop: `1px solid ${v('border')}` }
};