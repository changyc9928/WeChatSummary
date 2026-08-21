export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://192.168.0.216:8080';

// Local sidecar ("wechat-key-bridge") that lets the browser trigger a native
// WeChat memory scan on the user's machine. The browser cannot scan process
// memory itself; this service (127.0.0.1 only) does it on the frontend's
// behalf. Override at build time with VITE_LOCAL_BRIDGE_URL.
export const LOCAL_BRIDGE_URL = import.meta.env.VITE_LOCAL_BRIDGE_URL || 'http://127.0.0.1:8787';