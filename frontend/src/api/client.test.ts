import axios from 'axios'

vi.mock('axios')

describe('client governance api', () => {
  it('requests governance runtime summary with optional organization key', async () => {
    vi.resetModules()
    const mockedGet = vi.fn().mockResolvedValue({ data: { code: 0, data: { organizationKey: 'org-1' } } })
    vi.mocked(axios.create).mockReturnValue({
      get: mockedGet,
      post: vi.fn(),
      delete: vi.fn(),
      interceptors: {
        response: {
          use: vi.fn(),
        },
      },
    } as unknown as ReturnType<typeof axios.create>)

    const { getGovernanceSummary: lazyGetGovernanceSummary } = await import('./client')
    await lazyGetGovernanceSummary('org-1')

    expect(mockedGet).toHaveBeenCalledWith('/governance/runtime-summary', {
      params: { organizationKey: 'org-1' },
    })
  })
})
