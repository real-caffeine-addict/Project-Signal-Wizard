import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test, vi } from 'vitest'
import App from './App'
import { createDocument, readAuditEvent, readDocument } from './util/api'

vi.mock('./util/api', () => ({
  createDocument: vi.fn(),
  readDocument: vi.fn(),
  readAuditEvent: vi.fn(),
}))

beforeEach(() => vi.resetAllMocks())

test('submits trimmed document fields and selected revision metadata while preventing duplicate clicks', async () => {
  const user = userEvent.setup()
  const pending = Promise.withResolvers()
  createDocument.mockReturnValue(pending.promise)
  render(<App />)
  for (const [label, value] of [
    ['Project ID', '7'], ['Dossier ID', '8'], ['Title', '  Drawing  '],
    ['Document Number', '  DOC-001  '], ['Originating Company ID', '6'], ['Revision Code', 'B'],
  ]) {
    await user.clear(screen.getByLabelText(label))
    await user.type(screen.getByLabelText(label), value)
  }
  await user.selectOptions(screen.getByLabelText('Type'), 'SPEC')
  await user.selectOptions(screen.getByLabelText('Status'), 'IFC')
  await user.clear(screen.getByLabelText('Issued At'))
  await user.type(screen.getByLabelText('Issued At'), '2026-07-01T12:30')

  await user.click(screen.getByRole('button', { name: 'Create Document' }))

  expect(createDocument).toHaveBeenCalledExactlyOnceWith({
    projectId: 7, dossierId: 8, title: 'Drawing', documentNumber: 'DOC-001', type: 'SPEC',
    originatingCompanyId: 6, revisionCode: 'B', status: 'IFC', issuedAt: '2026-07-01T12:30:00',
  })
  const creating = screen.getByRole('button', { name: 'Creating...' })
  expect(creating).toBeDisabled()
  await user.click(creating)
  expect(createDocument).toHaveBeenCalledTimes(1)

  await act(async () => pending.resolve('Document created'))

  expect(screen.getByRole('status')).toHaveTextContent('Document created')
  expect(screen.getByRole('button', { name: 'Create Document' })).toBeEnabled()
})

test('sends null for cleared optional fields', async () => {
  const user = userEvent.setup()
  createDocument.mockResolvedValue('created')
  render(<App />)
  for (const label of ['Dossier ID', 'Originating Company ID', 'Revision Code', 'Issued At']) {
    await user.clear(screen.getByLabelText(label))
  }

  await user.click(screen.getByRole('button', { name: 'Create Document' }))

  expect(createDocument).toHaveBeenCalledWith(expect.objectContaining({
    projectId: 1, dossierId: null, originatingCompanyId: null, revisionCode: null, issuedAt: null,
  }))
})

test('shows a create error and lets the user retry with the same form values', async () => {
  const user = userEvent.setup()
  createDocument.mockRejectedValueOnce(new Error('Audit unavailable')).mockResolvedValueOnce('created')
  render(<App />)
  await user.type(screen.getByLabelText('Title'), 'Drawing')

  await user.click(screen.getByRole('button', { name: 'Create Document' }))

  expect(await screen.findByRole('status')).toHaveTextContent('Audit unavailable')
  expect(screen.getByLabelText('Title')).toHaveValue('Drawing')
  await user.click(screen.getByRole('button', { name: 'Create Document' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('created'))
  expect(screen.queryByText('Audit unavailable')).not.toBeInTheDocument()
})

test('reads a selected document and displays metadata including null values', async () => {
  const user = userEvent.setup()
  const pending = Promise.withResolvers()
  readDocument.mockReturnValue(pending.promise)
  render(<App />)
  await user.click(screen.getByRole('button', { name: 'Read Form' }))
  await user.clear(screen.getByLabelText('Document ID'))
  await user.type(screen.getByLabelText('Document ID'), '41')

  await user.click(screen.getByRole('button', { name: 'Read Document' }))

  expect(readDocument).toHaveBeenCalledExactlyOnceWith('41')
  expect(screen.getByRole('button', { name: 'Reading...' })).toBeDisabled()
  await act(async () => pending.resolve({ id: 41, title: 'Drawing', currentRevisionId: null }))
  const result = within(screen.getByRole('status'))
  expect(result.getByText('41')).toBeInTheDocument()
  expect(result.getByText('Drawing')).toBeInTheDocument()
  expect(result.getByText('null')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Read Document' })).toBeEnabled()
})

test('failed document read clears stale metadata and restores the read button', async () => {
  const user = userEvent.setup()
  readDocument.mockResolvedValueOnce({ title: 'Old document' }).mockRejectedValueOnce(new Error('Not found'))
  render(<App />)
  await user.click(screen.getByRole('button', { name: 'Read Form' }))
  await user.click(screen.getByRole('button', { name: 'Read Document' }))
  expect(await screen.findByText('Old document')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Read Document' }))

  expect(await screen.findByRole('status')).toHaveTextContent('Not found')
  expect(screen.queryByText('Old document')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Read Document' })).toBeEnabled()
})

test('reads the chosen audit event and displays its text payload', async () => {
  const user = userEvent.setup()
  const pending = Promise.withResolvers()
  readAuditEvent.mockReturnValue(pending.promise)
  render(<App />)
  await user.click(screen.getByRole('button', { name: 'Read Audit Event' }))
  await user.clear(screen.getByLabelText('Audit Event ID'))
  await user.type(screen.getByLabelText('Audit Event ID'), '63')
  const submit = within(screen.getByLabelText('Audit Event ID').closest('form')).getByRole('button')
  await user.click(submit)

  expect(readAuditEvent).toHaveBeenCalledExactlyOnceWith('63')
  expect(screen.getByRole('button', { name: 'Reading...' })).toBeDisabled()
  await act(async () => pending.resolve('{"note":"שרטוט"}\nsecond line'))
  expect(screen.getByRole('status').textContent).toBe('{"note":"שרטוט"}\nsecond line')
  expect(submit).toBeEnabled()
})

test('audit read failure replaces previous output and permits another request', async () => {
  const user = userEvent.setup()
  readAuditEvent.mockResolvedValueOnce('Old audit event').mockRejectedValueOnce(new Error('Audit unavailable'))
  render(<App />)
  const navigation = within(screen.getByRole('complementary'))
  await user.click(navigation.getByRole('button', { name: 'Read Audit Event' }))
  const submit = within(screen.getByLabelText('Audit Event ID').closest('form')).getByRole('button')
  await user.click(submit)
  expect(await screen.findByText('Old audit event')).toBeInTheDocument()

  await user.click(submit)

  expect(await screen.findByRole('status')).toHaveTextContent('Audit unavailable')
  expect(screen.queryByText('Old audit event')).not.toBeInTheDocument()
  expect(submit).toBeEnabled()
})
