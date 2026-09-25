import React, { useEffect, useState } from 'react';
import useAiSettings from '../../hooks/useAiSettings';
import useLanguage from '../../hooks/useLanguage';

function SecretField({ name, status, draftValue, onChange, t, show, onToggleShow }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
      <label style={{ fontSize: '0.78rem', fontWeight: '600', color: 'var(--text-primary)' }}>
        {t(`settings.${name}Label`)}
      </label>
      <div style={{ display: 'flex', gap: '6px' }}>
        <input
          type={show ? 'text' : 'password'}
          value={draftValue}
          onChange={e => onChange(name, e.target.value)}
          placeholder={status || t('settings.secretPlaceholder')}
          spellCheck={false}
          autoComplete="new-password"
          style={{
            flex: 1, minWidth: 0, padding: '7px 10px', borderRadius: '6px',
            border: '1px solid var(--border-strong)', backgroundColor: 'var(--bg-card)',
            color: 'var(--text-primary)', fontSize: '0.82rem', fontFamily: 'monospace'
          }}
        />
        <button
          type="button"
          onClick={onToggleShow}
          style={{
            background: 'var(--bg-subtle)', border: '1px solid var(--border-strong)',
            borderRadius: '6px', padding: '0 10px', cursor: 'pointer', fontSize: '0.8rem'
          }}
          title={show ? t('settings.hideKey') : t('settings.showKey')}
        >
          {show ? '🙈' : '👁'}
        </button>
      </div>
      <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
        {status
          ? t('settings.secretKeepHint', { masked: status })
          : t('settings.secretDefaultHint')}
      </span>
    </div>
  );
}

function TextField({ name, value, onChange, t, mono = false }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
      <label style={{ fontSize: '0.78rem', fontWeight: '600', color: 'var(--text-primary)' }}>
        {t(`settings.${name}Label`)}
      </label>
      <input
        value={value}
        onChange={e => onChange(name, e.target.value)}
        spellCheck={false}
        style={{
          width: '100%', boxSizing: 'border-box', padding: '7px 10px', borderRadius: '6px',
          border: '1px solid var(--border-strong)', backgroundColor: 'var(--bg-card)',
          color: 'var(--text-primary)', fontSize: '0.82rem',
          fontFamily: mono ? 'monospace' : undefined
        }}
      />
    </div>
  );
}

function Section({ title, hint, children }) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', gap: '10px',
      border: '1px solid var(--border)', borderRadius: '10px', padding: '12px'
    }}>
      <div style={{ fontSize: '0.88rem', fontWeight: '700', color: 'var(--text-primary)' }}>{title}</div>
      {hint && <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: '-6px' }}>{hint}</div>}
      {children}
    </div>
  );
}

export default function SettingsSidebar({ open, onClose }) {
  const { t } = useLanguage();
  const {
    server, draft, loading, saving, resetting, error, notice,
    load, setField, valueFor, save, resetAll, setNotice
  } = useAiSettings();
  const [visibleSecrets, setVisibleSecrets] = useState({});
  const [confirmingReset, setConfirmingReset] = useState(false);

  useEffect(() => {
    if (open) {
      setConfirmingReset(false);
      setVisibleSecrets({});
      load();
    }
  }, [open, load]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = e => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const handleSave = async () => {
    const data = await save();
    if (data) setNotice(t('settings.saved'));
  };

  const handleReset = async () => {
    if (!confirmingReset) {
      setConfirmingReset(true);
      return;
    }
    const data = await resetAll();
    setConfirmingReset(false);
    if (data) setNotice(t('settings.resetDone'));
  };

  const dirtyCount = Object.entries(draft).filter(([, v]) => String(v ?? '').trim() !== '').length;

  return (
    <div
      aria-hidden={!open}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        pointerEvents: open ? 'auto' : 'none'
      }}
    >
      <div
        onClick={onClose}
        style={{
          position: 'absolute', inset: 0, backgroundColor: 'rgba(0,0,0,0.35)',
          opacity: open ? 1 : 0, transition: 'opacity 0.2s ease'
        }}
      />
      <aside
        role="dialog"
        aria-label={t('settings.title')}
        style={{
          position: 'absolute', top: 0, right: 0, bottom: 0, width: 'min(420px, 94vw)',
          backgroundColor: 'var(--bg-card)', color: 'var(--text-secondary)',
          borderLeft: '1px solid var(--border-strong)',
          boxShadow: '-8px 0 24px rgba(0,0,0,0.15)',
          transform: open ? 'translateX(0)' : 'translateX(105%)',
          transition: 'transform 0.22s ease',
          display: 'flex', flexDirection: 'column'
        }}
      >
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '14px 16px', borderBottom: '1px solid var(--border)'
        }}>
          <span style={{ fontSize: '1rem', fontWeight: '700', color: 'var(--text-primary)' }}>
            ⚙ {t('settings.title')}
          </span>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('settings.close')}
            style={{
              background: 'var(--bg-subtle)', border: '1px solid var(--border-strong)',
              borderRadius: '6px', width: '28px', height: '28px', cursor: 'pointer',
              fontSize: '0.85rem', color: 'var(--text-primary)'
            }}
          >
            ✕
          </button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', lineHeight: '1.5' }}>
            {t('settings.subtitle')}
          </div>

          {error && (
            <div style={{
              fontSize: '0.78rem', color: '#991b1b', backgroundColor: '#fef2f2',
              border: '1px solid #fecaca', borderRadius: '8px', padding: '8px 10px'
            }}>
              ⚠️ {error}
            </div>
          )}
          {notice && (
            <div style={{
              fontSize: '0.78rem', color: '#065f46', backgroundColor: '#ecfdf5',
              border: '1px solid #a7f3d0', borderRadius: '8px', padding: '8px 10px'
            }}>
              ✓ {notice}
            </div>
          )}

          {loading ? (
            <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', padding: '12px 0' }}>
              {t('settings.loading')}
            </div>
          ) : (
            <>
              <Section title={t('settings.chatTitle')} hint={t('settings.chatHint')}>
                <SecretField
                  name="chatApiKey"
                  t={t}
                  status={server?.chatApiKey}
                  draftValue={draft.chatApiKey ?? ''}
                  onChange={setField}
                  show={!!visibleSecrets.chatApiKey}
                  onToggleShow={() => setVisibleSecrets(p => ({ ...p, chatApiKey: !p.chatApiKey }))}
                />
                <TextField name="chatBaseUrl" t={t} mono value={valueFor('chatBaseUrl')} onChange={setField} />
                <TextField name="chatModel" t={t} mono value={valueFor('chatModel')} onChange={setField} />
              </Section>

              <Section title={t('settings.imageTitle')} hint={t('settings.imageHint')}>
                <SecretField
                  name="imageApiKey"
                  t={t}
                  status={server?.imageApiKey}
                  draftValue={draft.imageApiKey ?? ''}
                  onChange={setField}
                  show={!!visibleSecrets.imageApiKey}
                  onToggleShow={() => setVisibleSecrets(p => ({ ...p, imageApiKey: !p.imageApiKey }))}
                />
                <TextField name="imageBaseUrl" t={t} mono value={valueFor('imageBaseUrl')} onChange={setField} />
                <TextField name="imageModel" t={t} mono value={valueFor('imageModel')} onChange={setField} />
              </Section>

              <Section title={t('settings.videoTitle')} hint={t('settings.videoHint')}>
                <SecretField
                  name="videoApiKey"
                  t={t}
                  status={server?.videoApiKey}
                  draftValue={draft.videoApiKey ?? ''}
                  onChange={setField}
                  show={!!visibleSecrets.videoApiKey}
                  onToggleShow={() => setVisibleSecrets(p => ({ ...p, videoApiKey: !p.videoApiKey }))}
                />
                <TextField name="videoBaseUrl" t={t} mono value={valueFor('videoBaseUrl')} onChange={setField} />
                <TextField name="videoModel" t={t} mono value={valueFor('videoModel')} onChange={setField} />
              </Section>

              <Section title={t('settings.whisperTitle')} hint={t('settings.whisperHint')}>
                <SecretField
                  name="transcriptionApiKey"
                  t={t}
                  status={server?.transcriptionApiKey}
                  draftValue={draft.transcriptionApiKey ?? ''}
                  onChange={setField}
                  show={!!visibleSecrets.transcriptionApiKey}
                  onToggleShow={() => setVisibleSecrets(p => ({ ...p, transcriptionApiKey: !p.transcriptionApiKey }))}
                />
                <TextField name="transcriptionBaseUrl" t={t} mono value={valueFor('transcriptionBaseUrl')} onChange={setField} />
                <TextField name="transcriptionModel" t={t} mono value={valueFor('transcriptionModel')} onChange={setField} />
              </Section>
            </>
          )}
        </div>

        <div style={{
          display: 'flex', gap: '8px', padding: '12px 16px',
          borderTop: '1px solid var(--border)', flexWrap: 'wrap'
        }}>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving || loading}
            style={{
              flex: 1, backgroundColor: '#059669', color: '#fff', border: 'none',
              padding: '9px 14px', borderRadius: '8px', fontWeight: '600',
              cursor: saving || loading ? 'not-allowed' : 'pointer',
              opacity: saving || loading ? 0.6 : 1, fontSize: '0.85rem'
            }}
          >
            {saving ? t('settings.saving') : t('settings.save', { n: dirtyCount })}
          </button>
          <button
            type="button"
            onClick={handleReset}
            disabled={resetting || loading}
            style={{
              backgroundColor: confirmingReset ? '#dc2626' : 'var(--bg-card)',
              color: confirmingReset ? '#fff' : 'var(--text-secondary)',
              border: confirmingReset ? 'none' : '1px solid var(--border-strong)',
              padding: '9px 14px', borderRadius: '8px', fontWeight: '600',
              cursor: resetting || loading ? 'not-allowed' : 'pointer',
              opacity: resetting || loading ? 0.6 : 1, fontSize: '0.85rem'
            }}
          >
            {resetting ? t('settings.resetting') : confirmingReset ? t('settings.resetConfirm') : t('settings.reset')}
          </button>
        </div>
      </aside>
    </div>
  );
}
