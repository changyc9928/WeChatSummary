import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { aiSettingsApi } from '../settings'

const envelope = (data, message = 'success') => ({ code: 0, message, data })

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

describe('aiSettingsApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('get', () => {
    it('returns the settings data from the success envelope', async () => {
      const data = { chatApiKey: '••••abcd', chatBaseUrl: 'https://x/v1', workers: 3 }
      vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(envelope(data)))
      await expect(aiSettingsApi.get()).resolves.toEqual(data)
      expect(vi.mocked(fetch)).toHaveBeenCalledOnce()
      const [url, init] = vi.mocked(fetch).mock.calls[0]
      expect(String(url)).toMatch(/\/api\/settings\/ai$/)
      expect(init?.method ?? 'GET').toBe('GET')
    })

    it('throws the backend message on HTTP errors', async () => {
      vi.mocked(fetch).mockResolvedValueOnce(
        jsonResponse({ code: 400, message: 'workers must be between 1 and 32', data: null }, 400)
      )
      await expect(aiSettingsApi.get()).rejects.toThrow('workers must be between 1 and 32')
    })

    it('throws a network hint when the server is unreachable', async () => {
      vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))
      await expect(aiSettingsApi.get()).rejects.toThrow(/Unable to reach the server/)
    })
  })

  describe('update', () => {
    it('PUTs the payload as JSON and returns the saved view', async () => {
      const saved = { chatApiKey: '••••wxyz', workers: 5 }
      vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(envelope(saved, 'AI settings saved')))
      const payload = { chatApiKey: 'sk-new', workers: 5 }
      await expect(aiSettingsApi.update(payload)).resolves.toEqual(saved)
      const [url, init] = vi.mocked(fetch).mock.calls[0]
      expect(String(url)).toMatch(/\/api\/settings\/ai$/)
      expect(init.method).toBe('PUT')
      expect(init.headers['Content-Type']).toBe('application/json')
      expect(JSON.parse(init.body)).toEqual(payload)
    })

    it('surfaces validation failures', async () => {
      vi.mocked(fetch).mockResolvedValueOnce(
        jsonResponse({ code: 400, message: 'maxWorkers must be greater than or equal to workers', data: null }, 400)
      )
      await expect(aiSettingsApi.update({ workers: 8, maxWorkers: 2 })).rejects.toThrow(
        'maxWorkers must be greater than or equal to workers'
      )
    })
  })

  describe('reset', () => {
    it('DELETEs and returns the defaulted view', async () => {
      const data = { chatApiKey: null, workers: 3 }
      vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(envelope(data, 'AI settings reset to server defaults')))
      await expect(aiSettingsApi.reset()).resolves.toEqual(data)
      const [, init] = vi.mocked(fetch).mock.calls[0]
      expect(init.method).toBe('DELETE')
    })
  })
})
