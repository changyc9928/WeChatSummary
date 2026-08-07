import { apiRequest } from './client';

export async function startSummary(uuid, payload, userUuid) {
  const response = await apiRequest(`/api/summary/${uuid}`, {
    method: 'POST',
    userUuid,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!response.ok) throw new Error('Failed to start summary engine.');
}

export async function restartSummary(uuid, payload, userUuid) {
  const response = await apiRequest(`/api/summary/restart/${uuid}`, {
    method: 'POST',
    userUuid,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!response.ok) throw new Error('Failed to restart summary.');
}

export async function pauseSummary(uuid, userUuid) {
  const response = await apiRequest(`/api/summary/pause/${uuid}`, {
    method: 'POST',
    userUuid
  });
  if (!response.ok) throw new Error('Failed to pause summary.');
}

export async function getSummaryStatus(uuid, userUuid) {
  const response = await apiRequest(`/api/summary/status-pool/${uuid}`, { userUuid });
  if (!response.ok) return null;
  return response.json();
}