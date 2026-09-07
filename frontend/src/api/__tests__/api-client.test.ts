import { describe, it, expect } from 'vitest'
import {
  AuthControllerApi,
  BridgeLogControllerApi,
  ChatSummaryControllerApi,
  PreprocessControllerApi,
  ToolControllerApi,
  UploadControllerApi,
} from '../generated/apis'
import {
  Configuration,
  BASE_PATH,
} from '../generated/runtime'

describe('Generated API Client Smoke Tests', () => {
  describe('API class imports', () => {
    it('should import AuthControllerApi', () => {
      expect(AuthControllerApi).toBeDefined()
    })

    it('should import BridgeLogControllerApi', () => {
      expect(BridgeLogControllerApi).toBeDefined()
    })

    it('should import ChatSummaryControllerApi', () => {
      expect(ChatSummaryControllerApi).toBeDefined()
    })

    it('should import PreprocessControllerApi', () => {
      expect(PreprocessControllerApi).toBeDefined()
    })

    it('should import ToolControllerApi', () => {
      expect(ToolControllerApi).toBeDefined()
    })

    it('should import UploadControllerApi', () => {
      expect(UploadControllerApi).toBeDefined()
    })
  })

  describe('Configuration', () => {
    it('should export Configuration class', () => {
      expect(Configuration).toBeDefined()
      expect(typeof Configuration).toBe('function')
    })

    it('should export BASE_PATH default', () => {
      expect(BASE_PATH).toBe('http://localhost:8080')
    })

    it('should create a configuration with custom basePath', () => {
      const config = new Configuration({ basePath: 'http://custom:9090' })
      expect(config.basePath).toBe('http://custom:9090')
    })

    it('should default to BASE_PATH when no basePath provided', () => {
      const config = new Configuration({})
      expect(config.basePath).toBe(BASE_PATH)
    })

    it('should accept middleware configuration', () => {
      const middleware = [{ pre: async (ctx) => ctx, post: async (ctx) => ctx }]
      const config = new Configuration({ middleware })
      expect(config.middleware).toBe(middleware)
    })

    it('should accept headers configuration', () => {
      const headers = { 'X-Custom': 'value' }
      const config = new Configuration({ headers })
      expect(config.headers).toBe(headers)
    })
  })

  describe('API class instantiation', () => {
    it('should instantiate AuthControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new AuthControllerApi(config)
      expect(api).toBeInstanceOf(AuthControllerApi)
    })

    it('should instantiate BridgeLogControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new BridgeLogControllerApi(config)
      expect(api).toBeInstanceOf(BridgeLogControllerApi)
    })

    it('should instantiate ChatSummaryControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new ChatSummaryControllerApi(config)
      expect(api).toBeInstanceOf(ChatSummaryControllerApi)
    })

    it('should instantiate PreprocessControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new PreprocessControllerApi(config)
      expect(api).toBeInstanceOf(PreprocessControllerApi)
    })

    it('should instantiate ToolControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new ToolControllerApi(config)
      expect(api).toBeInstanceOf(ToolControllerApi)
    })

    it('should instantiate UploadControllerApi with configuration', () => {
      const config = new Configuration({ basePath: 'http://test:8080' })
      const api = new UploadControllerApi(config)
      expect(api).toBeInstanceOf(UploadControllerApi)
    })
  })

  describe('API methods exist', () => {
    it('AuthControllerApi should have login and register methods', () => {
      const api = new AuthControllerApi()
      expect(typeof api.login).toBe('function')
      expect(typeof api.register).toBe('function')
    })

    it('BridgeLogControllerApi should have since, ingest, and clear methods', () => {
      const api = new BridgeLogControllerApi()
      expect(typeof api.since).toBe('function')
      expect(typeof api.ingest).toBe('function')
      expect(typeof api.clear).toBe('function')
    })

    it('ChatSummaryControllerApi should have summary methods', () => {
      const api = new ChatSummaryControllerApi()
      expect(typeof api.startSummary).toBe('function')
      expect(typeof api.restartSummary).toBe('function')
      expect(typeof api.pauseSummary).toBe('function')
      expect(typeof api.getStatusAndProgress).toBe('function')
      expect(typeof api.getChatPreview).toBe('function')
    })

    it('PreprocessControllerApi should have preprocess and media methods', () => {
      const api = new PreprocessControllerApi()
      expect(typeof api.preprocess).toBe('function')
      expect(typeof api.restartPreprocess).toBe('function')
      expect(typeof api.reprocess).toBe('function')
      expect(typeof api.abortTask).toBe('function')
      expect(typeof api.getProgress).toBe('function')
    })

    it('ToolControllerApi should have meta and download methods', () => {
      const api = new ToolControllerApi()
      expect(typeof api.meta).toBe('function')
      expect(typeof api.download).toBe('function')
    })

    it('UploadControllerApi should have upload, getAvailableSessions, and deleteSession methods', () => {
      const api = new UploadControllerApi()
      expect(typeof api.upload).toBe('function')
      expect(typeof api.getAvailableSessions).toBe('function')
      expect(typeof api.deleteSession).toBe('function')
    })
  })

  describe('Model imports', () => {
    it('should import all model types from generated index', async () => {
      const models = await import('../generated/models/index')

      const expectedInterfaces = [
        'ApiResponseAuthResponse',
        'ApiResponseBoolean',
        'ApiResponseBridgeToolMeta',
        'ApiResponseChatPreviewResponse',
        'ApiResponseInteger',
        'ApiResponseListSessionResponseDTO',
        'ApiResponseLogsResponse',
        'ApiResponsePageAudioSummary',
        'ApiResponsePageEmojiSummaryEntity',
        'ApiResponsePageImageSummaryEntity',
        'ApiResponsePageVideoSummary',
        'ApiResponseSummaryProgressResponse',
        'ApiResponseTaskAckResponse',
        'ApiResponseTaskProgress',
        'ApiResponseUploadSessionResponse',
        'ApiResponseVoid',
        'AudioSummary',
        'AuthRequest',
        'AuthResponse',
        'BridgeToolMeta',
        'ChatPreviewResponse',
        'ChatPreviewRow',
        'EmojiSummaryEntity',
        'ImageSummaryEntity',
        'JsonNode',
        'LogsResponse',
        'PageAudioSummary',
        'PageEmojiSummaryEntity',
        'PageImageSummaryEntity',
        'PageVideoSummary',
        'PageableObject',
        'SessionResponseDTO',
        'SortObject',
        'StoredLine',
        'SummaryProgressResponse',
        'SummaryRequestDTO',
        'TaskAckResponse',
        'TaskProgress',
        'UploadSessionResponse',
        'VideoSummary',
      ]

      const allKeys = Object.keys(models)
      for (const name of expectedInterfaces) {
        expect(allKeys.some(k => k === name || k.startsWith(name))).toBe(true)
      }
    })
  })

  describe('apiClient integration', () => {
    it('should export apiClient with all required API instances', async () => {
      const { apiClient } = await import('../../api/client')
      expect(apiClient).toBeDefined()
      expect(apiClient.auth).toBeInstanceOf(AuthControllerApi)
      expect(apiClient.chatSummary).toBeInstanceOf(ChatSummaryControllerApi)
      expect(apiClient.preprocess).toBeInstanceOf(PreprocessControllerApi)
      expect(apiClient.upload).toBeInstanceOf(UploadControllerApi)
      expect(apiClient.tools).toBeInstanceOf(ToolControllerApi)
    })

    it('apiClient should not have a bridgeLog property (not wired in client.js)', async () => {
      const { apiClient } = await import('../../api/client')
      expect((apiClient as any).bridgeLog).toBeUndefined()
    })
  })
})
