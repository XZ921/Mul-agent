import { getNodeStatusText } from './display'

describe('display', () => {
  it('translates success degraded node status into friendly copy', () => {
    expect(getNodeStatusText('SUCCESS_DEGRADED' as never)).toBe('节点降级成功')
  })
})
