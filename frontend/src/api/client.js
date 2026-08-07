import { Configuration } from './generated/runtime';
import {
  AuthControllerApi,
  ChatSummaryControllerApi,
  PreprocessControllerApi,
  UploadControllerApi
} from './generated/apis';
import { API_BASE_URL } from '../config';

async function fetchApi(input, init) {
  const response = await fetch(input, init);

  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const body = await response.clone().json();
      if (body && typeof body.message === 'string' && body.message) {
        message = body.message;
      } else if (body && typeof body.error === 'string' && body.error) {
        message = body.error;
      }
    } catch {
      // Non-JSON error body, keep generic status message.
    }
    throw new Error(message);
  }

  return response;
}

const configuration = new Configuration({
  basePath: API_BASE_URL,
  fetchApi,
});

export const apiClient = {
  auth: new AuthControllerApi(configuration),
  chatSummary: new ChatSummaryControllerApi(configuration),
  preprocess: new PreprocessControllerApi(configuration),
  upload: new UploadControllerApi(configuration),
};