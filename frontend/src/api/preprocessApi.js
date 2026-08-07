import { apiRequest } from './client';

export async function startPreprocess(uuid, userUuid) {
  const response = await apiRequest(`/api/preprocess/${uuid}`, { method: 'POST', userUuid });
  if (!response.ok) throw new Error('Failed to start preprocessing.');
}

export async function abortPreprocess(uuid, userUuid) {
  const response = await apiRequest(`/api/preprocess/${uuid}/abort`, { method: 'POST', userUuid });
  if (!response.ok) throw new Error('Failed to abort preprocessing.');
}

export async function getPreprocessProgress(uuid, userUuid) {
  const response = await apiRequest(`/api/preprocess/${uuid}/progress`, { userUuid });
  if (!response.ok) return null;
  return response.json();
}

export async function getChatPreview(uuid, userUuid) {
  const response = await apiRequest(`/api/summary/preview/${uuid}`, { userUuid });
  if (!response.ok) throw new Error('Failed to load chat preview.');
  return response.json();
}

export async function getImageSummaries(uuid, page, size, userUuid) {
  const response = await apiRequest(`/api/preprocess/images/summaries?uuid=${uuid}&page=${page}&size=${size}`, { userUuid });
  if (!response.ok) throw new Error('Failed to fetch session image summaries.');
  return response.json();
}

export async function getImageFile(id, userUuid) {
  return apiRequest(`/api/preprocess/images/${id}/file`, { userUuid });
}

export async function deleteImageSummary(id, userUuid) {
  const response = await apiRequest(`/api/preprocess/images/summaries/${id}`, { method: 'DELETE', userUuid });
  if (!response.ok) throw new Error('Failed to delete image summary.');
}

export async function batchDeleteImageSummaries(ids, userUuid) {
  const response = await apiRequest(`/api/preprocess/images/summaries`, {
    method: 'DELETE',
    userUuid,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ids)
  });
  if (!response.ok) throw new Error('Failed to delete image summaries.');
}

export async function getAudioSummaries(uuid, page, size, userUuid) {
  const response = await apiRequest(`/api/preprocess/audios/summaries?uuid=${uuid}&page=${page}&size=${size}`, { userUuid });
  if (!response.ok) throw new Error('Failed to fetch session audio summaries.');
  return response.json();
}

export async function deleteAudioSummary(id, userUuid) {
  const response = await apiRequest(`/api/preprocess/audios/summaries/${id}`, { method: 'DELETE', userUuid });
  if (!response.ok) throw new Error('Failed to delete audio summary.');
}

export async function clearAudioText(id, userUuid) {
  const response = await apiRequest(`/api/preprocess/audios/summaries/${id}/text`, { method: 'DELETE', userUuid });
  if (!response.ok) throw new Error('Failed to clear audio transcript text.');
}

export async function batchDeleteAudioSummaries(ids, userUuid) {
  const response = await apiRequest(`/api/preprocess/audios/summaries`, {
    method: 'DELETE',
    userUuid,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ids)
  });
  if (!response.ok) throw new Error('Failed to delete audio summaries.');
}

export async function batchClearAudioTexts(ids, userUuid) {
  const response = await apiRequest(`/api/preprocess/audios/summaries/text`, {
    method: 'DELETE',
    userUuid,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ids)
  });
  if (!response.ok) throw new Error('Failed to clear audio transcript texts.');
}