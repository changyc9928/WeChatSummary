import React, { useState, useRef, useEffect } from 'react';
import LoginForm from './LoginForm';
import DatasetSelector from './components/DatasetSelector';
import StepUpload from './components/StepUpload';
import StepPreprocess from './components/StepPreprocess';
import StepSummary from './components/StepSummary';
import ImageSummariesPanel from './components/ImageSummariesPanel';
import AudioSummariesPanel from './components/AudioSummariesPanel';
import ImageLightboxModal from './components/ImageLightboxModal';
import { styles } from './styles/dashboardStyles';

const API_BASE_URL = 'http://192.168.0.216:8080';

export default function App() {
  const [currentUser, setCurrentUser] = useState(null);
  const [file, setFile] = useState(null);
  const [uuidInput, setUuidInput] = useState('');
  const [sessions, setSessions] = useState([]);
  const [loadingSessions, setLoadingSessions] = useState(false);

  // View state: 'dashboard' | 'images' | 'audios'
  const [currentView, setCurrentView] = useState('dashboard');

  // Date/Time Window Selection States
  const [selectedStartTime, setSelectedStartTime] = useState('');
  const [selectedEndTime, setSelectedEndTime] = useState('');

  // Image states
  const [imageSummaries, setImageSummaries] = useState([]);
  const [selectedImageIds, setSelectedImageIds] = useState([]);
  const [loadingImages, setLoadingImages] = useState(false);
  const [imagePagination, setImagePagination] = useState({
    page: 0,
    size: 20,
    totalPages: 0,
    totalElements: 0,
    isFirst: true,
    isLast: true
  });

  // Audio states
  const [audioSummaries, setAudioSummaries] = useState([]);
  const [selectedAudioIds, setSelectedAudioIds] = useState([]);
  const [loadingAudios, setLoadingAudios] = useState(false);
  const [audioPagination, setAudioPagination] = useState({
    page: 0,
    size: 20,
    totalPages: 0,
    totalElements: 0,
    isFirst: true,
    isLast: true
  });

  const [activeModalImage, setActiveModalImage] = useState(null);
  const [loading, setLoading] = useState({
    upload: false,
    preprocess: false,
    abortPreprocess: false,
    start: false,
    pauseSummary: false,
    restartSummary: false,
    deleteImage: false,
    batchDeleteImages: false,
    deleteAudio: false,
    clearAudioText: false,
    batchDeleteAudios: false,
    batchClearAudioTexts: false
  });

  const [errors, setErrors] = useState({
    upload: null,
    preprocess: null,
    summary: null,
    sessions: null,
    images: null,
    audios: null
  });

  const [isPreprocessFinished, setIsPreprocessFinished] = useState(false);
  const [preprocessProgress, setPreprocessProgress] = useState(null);
  const [summaryState, setSummaryState] = useState({
    status: 'INITIAL_STATE',
    progress: 0.0,
    result: null,
    errorMessage: null
  });

  const preprocessIntervalRef = useRef(null);
  const summaryIntervalRef = useRef(null);

  useEffect(() => {
    const savedUser = localStorage.getItem('wechat_current_user');
    if (savedUser) {
      try {
        setCurrentUser(JSON.parse(savedUser));
      } catch (e) {
        localStorage.removeItem('wechat_current_user');
      }
    }
  }, []);

  useEffect(() => {
    if (currentUser) fetchSessions();
  }, [currentUser]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') setActiveModalImage(null);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  useEffect(() => {
    if (uuidInput && currentUser) {
      setPreprocessProgress(null);
      setIsPreprocessFinished(false);
      setSummaryState({ status: 'INITIAL_STATE', progress: 0.0, result: null, errorMessage: null });
      
      setSelectedStartTime('');
      setSelectedEndTime('');

      fetchImageSummaries(uuidInput, 0, imagePagination.size);
      fetchAudioSummaries(uuidInput, 0, audioPagination.size);

      const initializeSessionStatus = async () => {
        const progressData = await checkPreprocessProgress(uuidInput);
        if (progressData) {
          if (progressData.status === 'RUNNING') {
            startPreprocessPolling(uuidInput);
          } else if (progressData.status === 'COMPLETED') {
            setIsPreprocessFinished(true);
            setPreprocessProgress(progressData);
            await fetchSummaryStatus(uuidInput);
          } else if (progressData.status === 'PAUSED') {
            setPreprocessProgress(progressData);
          }
        }
      };
      initializeSessionStatus();
    } else {
      setIsPreprocessFinished(false);
      setPreprocessProgress(null);
      setImageSummaries([]);
      setAudioSummaries([]);
      setSelectedImageIds([]);
      setSelectedAudioIds([]);
      setSelectedStartTime('');
      setSelectedEndTime('');
      setImagePagination({ page: 0, size: 20, totalPages: 0, totalElements: 0, isFirst: true, isLast: true });
      setAudioPagination({ page: 0, size: 20, totalPages: 0, totalElements: 0, isFirst: true, isLast: true });
      stopSummaryPolling();
    }
    return () => {
      if (preprocessIntervalRef.current) clearInterval(preprocessIntervalRef.current);
      stopSummaryPolling();
    };
  }, [uuidInput, currentUser]);

  const handleLogout = () => {
    setCurrentUser(null);
    setUuidInput('');
    setSessions([]);
    setCurrentView('dashboard');
    localStorage.removeItem('wechat_current_user');
  };

  const fetchSessions = async () => {
    if (!currentUser) return;
    setLoadingSessions(true);
    try {
      const response = await fetch(`${API_BASE_URL}/api/files/sessions`, {
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!response.ok) throw new Error("Failed to load active project list.");
      setSessions(await response.json());
    } catch (err) {
      setErrors(prev => ({ ...prev, sessions: err.message }));
    } finally {
      setLoadingSessions(false);
    }
  };

  const fetchImageSummaries = async (sessionUuid, page = 0, size = imagePagination.size) => {
    if (!sessionUuid || !currentUser) {
      setImageSummaries([]);
      return;
    }
    setLoadingImages(true);
    try {
      const response = await fetch(`${API_BASE_URL}/api/preprocess/images/summaries?uuid=${sessionUuid}&page=${page}&size=${size}`, {
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!response.ok) throw new Error("Failed to fetch session image summaries.");
      const data = await response.json();
      setImageSummaries(data.content || []);
      setImagePagination({
        page: data.number,
        size: data.size,
        totalPages: data.totalPages,
        totalElements: data.totalElements,
        isFirst: data.first,
        isLast: data.last
      });
    } catch (err) {
      setErrors(prev => ({ ...prev, images: err.message }));
    } finally {
      setLoadingImages(false);
    }
  };

  const fetchAudioSummaries = async (sessionUuid, page = 0, size = audioPagination.size) => {
    if (!sessionUuid || !currentUser) {
      setAudioSummaries([]);
      return;
    }
    setLoadingAudios(true);
    try {
      const response = await fetch(`${API_BASE_URL}/api/preprocess/audios/summaries?uuid=${sessionUuid}&page=${page}&size=${size}`, {
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!response.ok) throw new Error("Failed to fetch session audio summaries.");
      const data = await response.json();
      setAudioSummaries(data.content || []);
      setAudioPagination({
        page: data.number,
        size: data.size,
        totalPages: data.totalPages,
        totalElements: data.totalElements,
        isFirst: data.first,
        isLast: data.last
      });
    } catch (err) {
      setErrors(prev => ({ ...prev, audios: err.message }));
    } finally {
      setLoadingAudios(false);
    }
  };

  const checkPreprocessProgress = async (uuid) => {
    try {
      const res = await fetch(`${API_BASE_URL}/api/preprocess/${uuid}/progress`, {
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (res.ok) {
        const data = await res.json();
        setPreprocessProgress(data);
        return data;
      }
    } catch (err) {
      console.error(err);
    }
    return null;
  };

  const startPreprocessPolling = (uuid) => {
    if (preprocessIntervalRef.current) clearInterval(preprocessIntervalRef.current);
    const checkProgress = async () => {
      const data = await checkPreprocessProgress(uuid);
      if (!data) return;

      const isFinished = data.status === 'COMPLETED' || data.progressPercentage >= 100;
      if (isFinished) {
        clearInterval(preprocessIntervalRef.current);
        setIsPreprocessFinished(true);
        setPreprocessProgress(data);
        fetchSummaryStatus(uuid);
      } else if (data.status === 'PAUSED') {
        clearInterval(preprocessIntervalRef.current);
      }
    };
    checkProgress();
    preprocessIntervalRef.current = setInterval(checkProgress, 1500);
  };

  const fetchSummaryStatus = async (uuid) => {
    try {
      const res = await fetch(`${API_BASE_URL}/api/summary/status-pool/${uuid}`, {
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (res.ok) {
        const data = await res.json();
        const payload = (data && (data.status || data.result))
          ? data
          : (Object.values(data)[0] || {});

        const status = (payload.status || 'INITIAL_STATE').toUpperCase();
        let rawProgress = payload.progress ?? payload.progressPercentage ?? 0;
        const progress = rawProgress > 1 ? rawProgress / 100 : rawProgress;

        const result = payload.result || summaryState.result || null;

        setSummaryState(prevState => ({
          ...prevState,
          status: status,
          progress: progress,
          result: result,
          errorMessage: payload.errorMessage || prevState.errorMessage || null
        }));

        if (status === 'RUNNING' && !summaryIntervalRef.current) {
          summaryIntervalRef.current = setInterval(() => fetchSummaryStatus(uuid), 2000);
        } else if (status !== 'RUNNING') {
          stopSummaryPolling();
        }
      }
    } catch (err) {
      console.error("Error fetching summary status:", err);
    }
  };

  const stopSummaryPolling = () => {
    if (summaryIntervalRef.current) {
      clearInterval(summaryIntervalRef.current);
      summaryIntervalRef.current = null;
    }
  };

  const handleUpload = async (e) => {
    e.preventDefault();
    if (!file) return alert('Please select a file first!');
    setLoading(prev => ({ ...prev, upload: true }));
    const formData = new FormData();
    formData.append('file', file);
    try {
      const response = await fetch(`${API_BASE_URL}/api/files/upload`, {
        method: 'POST',
        headers: { 'X-User-Id': currentUser.uuid },
        body: formData
      });
      if (!response.ok) throw new Error("Could not upload file.");
      const assignedUuid = await response.text();
      if (assignedUuid) {
        await fetchSessions();
        setUuidInput(assignedUuid.trim());
      }
    } catch (err) {
      setErrors(prev => ({ ...prev, upload: err.message }));
    } finally {
      setLoading(prev => ({ ...prev, upload: false }));
    }
  };

  const handleStartPreprocess = async () => {
    if (!uuidInput || !currentUser) return;
    setErrors(prev => ({ ...prev, preprocess: null }));
    setLoading(prev => ({ ...prev, preprocess: true }));
    try {
      const response = await fetch(`${API_BASE_URL}/api/preprocess/${uuidInput}`, {
        method: 'POST',
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!response.ok) throw new Error("Failed to start preprocessing.");

      startPreprocessPolling(uuidInput);
    } catch (err) {
      setErrors(prev => ({ ...prev, preprocess: err.message }));
    } finally {
      setLoading(prev => ({ ...prev, preprocess: false }));
    }
  };

  const handleAbortPreprocess = async () => {
    if (!uuidInput) return;
    setLoading(prev => ({ ...prev, abortPreprocess: true }));
    try {
      const res = await fetch(`${API_BASE_URL}/api/preprocess/${uuidInput}/abort`, {
        method: 'POST',
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!res.ok) throw new Error("Failed to abort preprocessing.");
      if (preprocessIntervalRef.current) clearInterval(preprocessIntervalRef.current);
      await checkPreprocessProgress(uuidInput);
    } catch (err) {
      setErrors(prev => ({ ...prev, preprocess: err.message }));
    } finally {
      setLoading(prev => ({ ...prev, abortPreprocess: false }));
    }
  };

  const handleStartSummary = async (payload = {}) => {
    if (!uuidInput) return;
    setLoading(prev => ({ ...prev, start: true }));
    try {
      const res = await fetch(`${API_BASE_URL}/api/summary/${uuidInput}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': currentUser.uuid
        },
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error("Failed to start summary engine.");
      fetchSummaryStatus(uuidInput);
    } catch (err) {
      setErrors(prev => ({ ...prev, summary: err.message }));
    } finally {
      setLoading(prev => ({ ...prev, start: false }));
    }
  };

  const handleRestartSummary = async (payload = {}) => {
    if (!uuidInput) return;
    setLoading(prev => ({ ...prev, restartSummary: true }));
    try {
      const res = await fetch(`${API_BASE_URL}/api/summary/restart/${uuidInput}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': currentUser.uuid
        },
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error("Failed to restart summary.");
      fetchSummaryStatus(uuidInput);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(prev => ({ ...prev, restartSummary: false }));
    }
  };

  const handlePauseSummary = async () => {
    if (!uuidInput) return;
    setLoading(prev => ({ ...prev, pauseSummary: true }));
    try {
      const res = await fetch(`${API_BASE_URL}/api/summary/pause/${uuidInput}`, {
        method: 'POST',
        headers: { 'X-User-Id': currentUser.uuid }
      });
      if (!res.ok) throw new Error("Failed to pause summary.");
      fetchSummaryStatus(uuidInput);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(prev => ({ ...prev, pauseSummary: false }));
    }
  };

  if (!currentUser) {
    return (
      <LoginForm
        apiUrl={API_BASE_URL}
        onLoginSuccess={(userData) => {
          setCurrentUser(userData);
          localStorage.setItem('wechat_current_user', JSON.stringify(userData));
        }}
      />
    );
  }

  if (currentView === 'images') {
    return (
      <div style={styles.container}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
          <h2 style={styles.title}>Image Summaries & Records</h2>
          <button onClick={() => setCurrentView('dashboard')} style={styles.buttonSecondary}>← Back to Dashboard</button>
        </div>
        <ImageSummariesPanel
          uuidInput={uuidInput}
          imageSummaries={imageSummaries}
          loadingImages={loadingImages}
          imagePagination={imagePagination}
          fetchImageSummaries={fetchImageSummaries}
          selectedImageIds={selectedImageIds}
          setSelectedImageIds={setSelectedImageIds}
          handleDeleteImage={async (id) => {
            if (!currentUser) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/images/summaries/${id}`, {
                method: 'DELETE',
                headers: { 'X-User-Id': currentUser.uuid }
              });
              setSelectedImageIds(prev => prev.filter(item => item !== id));
              fetchImageSummaries(uuidInput, imagePagination.page, imagePagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, images: err.message }));
            }
          }}
          handleBatchDeleteImages={async () => {
            if (!currentUser || selectedImageIds.length === 0) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/images/summaries`, {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser.uuid },
                body: JSON.stringify(selectedImageIds)
              });
              setSelectedImageIds([]);
              fetchImageSummaries(uuidInput, 0, imagePagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, images: err.message }));
            }
          }}
          setActiveModalImage={setActiveModalImage}
          loading={loading}
          errorImages={errors.images}
          apiUrl={API_BASE_URL}
          currentUser={currentUser}
        />
        <ImageLightboxModal activeModalImage={activeModalImage} setActiveModalImage={setActiveModalImage} />
      </div>
    );
  }

  if (currentView === 'audios') {
    return (
      <div style={styles.container}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
          <h2 style={styles.title}>Audio Transcripts & Summaries</h2>
          <button onClick={() => setCurrentView('dashboard')} style={styles.buttonSecondary}>← Back to Dashboard</button>
        </div>
        <AudioSummariesPanel
          uuidInput={uuidInput}
          audioSummaries={audioSummaries}
          loadingAudios={loadingAudios}
          audioPagination={audioPagination}
          fetchAudioSummaries={fetchAudioSummaries}
          selectedAudioIds={selectedAudioIds}
          setSelectedAudioIds={setSelectedAudioIds}
          handleDeleteAudio={async (id) => {
            if (!currentUser) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/audios/summaries/${id}`, {
                method: 'DELETE',
                headers: { 'X-User-Id': currentUser.uuid }
              });
              setSelectedAudioIds(prev => prev.filter(item => item !== id));
              fetchAudioSummaries(uuidInput, audioPagination.page, audioPagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, audios: err.message }));
            }
          }}
          handleClearAudioText={async (id) => {
            if (!currentUser) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/audios/summaries/${id}/text`, {
                method: 'DELETE',
                headers: { 'X-User-Id': currentUser.uuid }
              });
              fetchAudioSummaries(uuidInput, audioPagination.page, audioPagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, audios: err.message }));
            }
          }}
          handleBatchDeleteAudios={async () => {
            if (!currentUser || selectedAudioIds.length === 0) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/audios/summaries`, {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser.uuid },
                body: JSON.stringify(selectedAudioIds)
              });
              setSelectedAudioIds([]);
              fetchAudioSummaries(uuidInput, 0, audioPagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, audios: err.message }));
            }
          }}
          handleBatchClearAudioTexts={async () => {
            if (!currentUser || selectedAudioIds.length === 0) return;
            try {
              await fetch(`${API_BASE_URL}/api/preprocess/audios/summaries/text`, {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser.uuid },
                body: JSON.stringify(selectedAudioIds)
              });
              setSelectedAudioIds([]);
              fetchAudioSummaries(uuidInput, 0, audioPagination.size);
              if (uuidInput) await checkPreprocessProgress(uuidInput);
            } catch (err) {
              setErrors(prev => ({ ...prev, audios: err.message }));
            }
          }}
          loading={loading}
          errorAudios={errors.audios}
        />
      </div>
    );
  }

  return (
    <div style={styles.container}>
      <header style={styles.header}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
          <div>
            <h1 style={{ ...styles.title, margin: 0, textAlign: 'left' }}>Data Summary Dashboard</h1>
            <p style={{ ...styles.subtitle, textAlign: 'left', margin: '4px 0 0 0' }}>
              Logged in as: <strong>{currentUser.username}</strong>
            </p>
          </div>
          <button onClick={handleLogout} style={styles.buttonDangerSmall}>Logout</button>
        </div>
      </header>

      <DatasetSelector
        uuidInput={uuidInput}
        setUuidInput={setUuidInput}
        sessions={sessions}
        loadingSessions={loadingSessions}
        fetchSessions={fetchSessions}
      />

      {/* Row 1: Step 1 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepUpload
          file={file}
          setFile={setFile}
          handleUpload={handleUpload}
          loading={loading.upload}
          errorUpload={errors.upload}
        />
      </div>

      {/* Row 2: Step 2 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepPreprocess
          uuidInput={uuidInput}
          isPreprocessFinished={isPreprocessFinished}
          preprocessProgress={preprocessProgress}
          handleStartPreprocess={handleStartPreprocess}
          handleAbortPreprocess={handleAbortPreprocess}
          loading={loading}
          errorPreprocess={errors.preprocess}
          onNavigateToImages={() => setCurrentView('images')}
          onNavigateToAudios={() => setCurrentView('audios')}
          apiUrl={API_BASE_URL}
          currentUser={currentUser}
          selectedStartTime={selectedStartTime}
          setSelectedStartTime={setSelectedStartTime}
          selectedEndTime={selectedEndTime}
          setSelectedEndTime={setSelectedEndTime}
        />
      </div>

      {/* Row 3: Step 3 (Full Width) */}
      <div style={{ width: '100%', display: 'block' }}>
        <StepSummary
          uuidInput={uuidInput}
          isPreprocessFinished={isPreprocessFinished}
          summaryState={summaryState}
          handleStartSummary={handleStartSummary}
          handlePauseSummary={handlePauseSummary}
          handleRestartSummary={handleRestartSummary}
          loading={loading}
          selectedStartTime={selectedStartTime}
          selectedEndTime={selectedEndTime}
        />
      </div>

      <ImageLightboxModal activeModalImage={activeModalImage} setActiveModalImage={setActiveModalImage} />
    </div>
  );
}