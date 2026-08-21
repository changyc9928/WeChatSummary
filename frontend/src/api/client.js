import { Configuration } from './generated/runtime';
import {
  AuthControllerApi,
  ChatSummaryControllerApi,
  PreprocessControllerApi,
  ToolControllerApi,
  UploadControllerApi
} from './generated/apis';
import { API_BASE_URL } from '../config';

const GENERIC_SERVER_ERROR = 'The server encountered an error. Please try again later.';
const GENERIC_NETWORK_ERROR = 'Unable to reach the server. Please check your network connection and try again.';

// Interceptor that rethrows the original error instead of letting the generated
// runtime wrap it in a generic "interceptors did not return an alternative
// response" FetchError that hides the real cause from the user.
const rethrowErrorMiddleware = {
  onError({ error }) {
    return Promise.reject(error instanceof Error ? error : new Error(String(error)));
  }
};

function formatValidationViolations(data) {
  if (!Array.isArray(data)) return null;
  const violations = data
    .filter(v => v && (v.message || v.field))
    .map(v => v.field ? `${v.field}: ${v.message || 'invalid'}` : v.message);
  return violations.length ? violations.join('; ') : null;
}

async function extractErrorMessage(response) {
  const status = response.status;

  // Surfaces the backend-provided message for client errors (4xx) so users
  // get actionable details (validation, auth, not found, ...).
  if (status >= 400 && status < 500) {
    try {
      const body = await response.clone().json();
      if (body) {
        if (typeof body.message === 'string' && body.message) {
          const details = formatValidationViolations(body.data);
          return details ? `${body.message}: ${details}` : body.message;
        }
        if (typeof body.error === 'string' && body.error) {
          return body.error;
        }
      }
    } catch {
      // Non-JSON error body, fall through to the generic status message.
    }
    return `Request failed with status ${status}`;
  }

  // Keep server errors (5xx) generic; don't leak internals to the user.
  return GENERIC_SERVER_ERROR;
}

async function fetchApi(input, init) {
  let response;
  try {
    response = await fetch(input, init);
  } catch {
    // fetch itself failed (offline, unreachable host, CORS). Surface a
    // user-friendly message instead of the raw TypeError.
    throw new Error(GENERIC_NETWORK_ERROR);
  }

  if (!response.ok) {
    throw new Error(await extractErrorMessage(response));
  }

  return response;
}

const configuration = new Configuration({
  basePath: API_BASE_URL,
  fetchApi,
  middleware: [rethrowErrorMiddleware],
});

export const apiClient = {
  auth: new AuthControllerApi(configuration),
  chatSummary: new ChatSummaryControllerApi(configuration),
  preprocess: new PreprocessControllerApi(configuration),
  upload: new UploadControllerApi(configuration),
  tools: new ToolControllerApi(configuration),
};