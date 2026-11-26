import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createDocument, readAuditEvent, readDocument } from './api'

beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
afterEach(() => vi.unstubAllGlobals())

test('creates a document with JSON and returns the service message', async () => {
  const payload = { projectId: 7, title: 'שרטוט', documentNumber: 'DOC-001' }
  fetch.mockResolvedValue(new Response('Document created', { status: 200 }))

  await expect(createDocument(payload)).resolves.toBe('Document created')

  expect(fetch).toHaveBeenCalledExactlyOnceWith('http://localhost:8083/documents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
})

test('reads JSON documents according to response content type', async () => {
  const document = { id: 41, currentRevisionId: null, title: 'שרטוט' }
  fetch.mockResolvedValue(new Response(JSON.stringify(document), {
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  }))

  await expect(readDocument(41)).resolves.toEqual(document)

  expect(fetch).toHaveBeenCalledExactlyOnceWith('http://localhost:8083/documents/41')
})

test('returns a text document response when the content type header is missing', async () => {
  const response = new Response('plain response')
  response.headers.delete('Content-Type')
  fetch.mockResolvedValue(response)

  await expect(readDocument(41)).resolves.toBe('plain response')
})

test('reads audit events as text without altering their payload', async () => {
  const payload = '{"note":"שרטוט"}\nsecond line'
  fetch.mockResolvedValue(new Response(payload))

  await expect(readAuditEvent(63)).resolves.toBe(payload)

  expect(fetch).toHaveBeenCalledExactlyOnceWith('http://localhost:8084/audit-events/63')
})

const operations = [
  ['create document', () => createDocument({}), 'Failed to create document'],
  ['read document', () => readDocument(41), 'Failed to read document'],
  ['read audit event', () => readAuditEvent(63), 'Failed to read audit event'],
]

test.each(operations)('%s preserves server error messages', async (_name, operation) => {
  fetch.mockResolvedValue(new Response('Service unavailable', { status: 503 }))

  await expect(operation()).rejects.toThrow('Service unavailable')
})

test.each(operations)('%s supplies a fallback for an empty error response', async (_name, operation, fallback) => {
  fetch.mockResolvedValue(new Response('', { status: 500 }))

  await expect(operation()).rejects.toThrow(fallback)
})

test.each(operations)('%s propagates network failures', async (_name, operation) => {
  const failure = new TypeError('Network unavailable')
  fetch.mockRejectedValue(failure)

  await expect(operation()).rejects.toBe(failure)
})

test('uses the document fallback for structured error responses', async () => {
  fetch.mockResolvedValue(new Response('{"error":"not found"}', {
    status: 404,
    headers: { 'Content-Type': 'application/json' },
  }))

  await expect(readDocument(41)).rejects.toThrow('Failed to read document')
})

test('propagates malformed JSON instead of treating it as a valid document', async () => {
  fetch.mockResolvedValue(new Response('broken json', {
    headers: { 'Content-Type': 'application/json' },
  }))

  await expect(readDocument(41)).rejects.toThrow(SyntaxError)
})
