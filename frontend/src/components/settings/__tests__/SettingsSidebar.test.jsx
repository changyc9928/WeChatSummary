// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react'
import React from 'react'
import LanguageProvider from '../../../context/LanguageContext.jsx'
import SettingsSidebar from '../SettingsSidebar'

const serverView = {
  chatApiKey: '••••abcd',
  chatBaseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/',
  chatModel: 'gemini-3.5-flash-lite',
  imageApiKey: null,
  imageBaseUrl: 'https://integrate.api.nvidia.com/v1',
  imageModel: 'meta/llama-3.2-11b-vision-instruct',
  videoApiKey: null,
  videoBaseUrl: 'https://integrate.api.nvidia.com/v1',
  videoModel: 'meta/llama-3.2-11b-vision-instruct',
  transcriptionApiKey: null,
  transcriptionBaseUrl: 'http://localhost:48000/v1/',
  transcriptionModel: 'large-v3',
  workers: 3,
  maxWorkers: 10,
  prefetch: 5,
  aiMaxParallel: 10,
  aiThrottlePercent: 10
}

const envelope = (data, message = 'success') => ({ code: 0, message, data })

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function renderSidebar(props = {}) {
  const onClose = vi.fn()
  const utils = render(
    <LanguageProvider>
      <SettingsSidebar open onClose={onClose} {...props} />
    </LanguageProvider>
  )
  return { onClose, ...utils }
}

function section(name) {
  return screen.getByText(name).closest('div').parentElement
}

describe('SettingsSidebar', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
    vi.mocked(fetch).mockResolvedValue(jsonResponse(envelope(serverView)))
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('loads and shows the server values with masked secrets', async () => {
    renderSidebar()
    expect(await screen.findByText('Chat & Summary')).toBeInTheDocument()
    expect(screen.getByText('Image understanding')).toBeInTheDocument()
    expect(screen.getByText('Voice transcription')).toBeInTheDocument()
    expect(screen.getByText('Preprocessing concurrency')).toBeInTheDocument()

    // secrets: masked status line for the configured key, default hint otherwise
    expect(await screen.findByText(/Current: ••••abcd/)).toBeInTheDocument()
    expect(screen.getAllByText(/No key set/).length).toBeGreaterThan(0)

    // non-secret fields are prefilled from the server
    const chat = section('Chat & Summary')
    expect(within(chat).getByLabelText('Base URL')).toHaveValue(serverView.chatBaseUrl)
    expect(within(chat).getByLabelText('Model')).toHaveValue(serverView.chatModel)

    // concurrency numbers are prefilled
    const concurrency = section('Preprocessing concurrency')
    expect(within(concurrency).getByLabelText('Workers per queue')).toHaveValue(3)
    expect(within(concurrency).getByLabelText('Max parallel AI calls')).toHaveValue(10)
  })

  it('saves only changed fields and omits untouched secrets', async () => {
    renderSidebar()
    await screen.findByText('Chat & Summary')

    const chat = section('Chat & Summary')
    fireEvent.change(within(chat).getByLabelText('API key (optional)'), {
      target: { value: 'sk-new-chat-key' }
    })
    const concurrency = section('Preprocessing concurrency')
    fireEvent.change(within(concurrency).getByLabelText('Workers per queue'), {
      target: { value: '6' }
    })

    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(envelope(serverView, 'AI settings saved')))
    fireEvent.click(screen.getByRole('button', { name: /Save/ }))

    await waitFor(() => {
      const puts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'PUT')
      expect(puts).toHaveLength(1)
    })
    const [, init] = vi.mocked(fetch).mock.calls.find(([, i]) => i?.method === 'PUT')
    expect(JSON.parse(init.body)).toEqual({ chatApiKey: 'sk-new-chat-key', workers: 6 })
  })

  it('shows the effective AI budget preview and updates it with the draft', async () => {
    renderSidebar()
    await screen.findByText('Chat & Summary')
    // defaults: maxWorkers=10 -> total=10, 10% -> 1, capped by aiMaxParallel=10
    expect(screen.getByText(/Effective AI budget right now: 1 parallel/)).toBeInTheDocument()

    const concurrency = section('Preprocessing concurrency')
    fireEvent.change(within(concurrency).getByLabelText('AI throttle % of workers'), {
      target: { value: '100' }
    })
    expect(await screen.findByText(/Effective AI budget right now: 10 parallel/)).toBeInTheDocument()
  })

  it('requires two clicks to reset and issues DELETE on confirm', async () => {
    renderSidebar()
    await screen.findByText('Chat & Summary')

    const resetBtn = screen.getByRole('button', { name: 'Reset to defaults' })
    fireEvent.click(resetBtn)
    expect(screen.getByRole('button', { name: 'Click again to confirm reset' })).toBeInTheDocument()
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1) // only the initial GET

    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(envelope(serverView, 'AI settings reset to server defaults')))
    fireEvent.click(screen.getByRole('button', { name: 'Click again to confirm reset' }))

    await waitFor(() => {
      const deletes = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')
      expect(deletes).toHaveLength(1)
    })
    expect(await screen.findByText(/Reset done/)).toBeInTheDocument()
  })

  it('closes via the close button, backdrop and Escape', async () => {
    const { onClose, container } = renderSidebar()
    await screen.findByText('Chat & Summary')

    fireEvent.click(screen.getByRole('button', { name: 'Close settings' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(2)

    // backdrop is the overlay div behind the dialog
    const backdrop = container.firstChild.firstChild
    fireEvent.click(backdrop)
    expect(onClose).toHaveBeenCalledTimes(3)
  })

  it('shows an error banner when loading fails', async () => {
    vi.mocked(fetch).mockReset()
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    renderSidebar()
    expect(await screen.findByText(/Unable to reach the server/)).toBeInTheDocument()
  })

  it('shows backend validation errors on save', async () => {
    renderSidebar()
    await screen.findByText('Chat & Summary')

    const concurrency = section('Preprocessing concurrency')
    fireEvent.change(within(concurrency).getByLabelText('Workers per queue'), {
      target: { value: '8' }
    })
    fireEvent.change(within(concurrency).getByLabelText('Max workers per queue'), {
      target: { value: '2' }
    })
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(
        { code: 400, message: 'maxWorkers must be greater than or equal to workers', data: null },
        400
      )
    )
    fireEvent.click(screen.getByRole('button', { name: /Save/ }))
    expect(
      await screen.findByText(/maxWorkers must be greater than or equal to workers/)
    ).toBeInTheDocument()
  })
})
