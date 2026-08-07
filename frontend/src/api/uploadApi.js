import { apiRequest } from './client';

export async function uploadFile(file, userUuid) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await apiRequest('/api/files/upload', {
    method: 'POST',
    userUuid,
    body: formData
  });
  if (!response.ok) throw new Error('Could not upload file.');

  return response.text();
}