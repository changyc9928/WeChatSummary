import { apiRequest } from './client';

export async function getSessions(userUuid) {
  const response = await apiRequest('/api/files/sessions', { userUuid });
  if (!response.ok) throw new Error('Failed to load active project list.');
  return response.json();
}