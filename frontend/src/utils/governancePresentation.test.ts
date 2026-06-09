import { describe, expect, it } from 'vitest'
import { buildGovernanceActionFailureMessage } from './governancePresentation'

describe('governancePresentation', () => {
  it('formats governance blocks as readable guidance without leaking internal quota keys', () => {
    const error = {
      response: {
        data: {
          message: '当前操作受到组织级治理限制，请稍后重试或调整执行策略',
          data: {
            governanceBlocked: true,
            governanceDecision: {
              decisionCode: 'CONNECTOR_BUSY',
              summary: '连接器运行时槽位已被占用：notion-pages / DEFAULT',
              recommendedAction: '等待当前同步结束后再重试，或切换到其他资料来源。',
              quotaKey: 'notion-pages:DEFAULT',
              blockingOwner: 'lease-default-org-notion-pages-DEFAULT-secret',
              requestedUnits: 1,
              availableUnits: 0,
              sourceUrls: ['https://ops.example.com/connectors/notion'],
            },
          },
        },
      },
    }

    const message = buildGovernanceActionFailureMessage(error, '资料接入失败', '请稍后重试')

    expect(message).toContain('资料接入失败')
    expect(message).toContain('连接器正在处理其他任务')
    expect(message).toContain('等待当前同步结束后再重试')
    expect(message).toContain('来源：https://ops.example.com/connectors/notion')
    expect(message).not.toContain('notion-pages:DEFAULT')
    expect(message).not.toContain('lease-default-org')
  })

  it('falls back to a stable retry suggestion when governance decision omits recommended action', () => {
    const error = {
      response: {
        data: {
          message: '当前操作受到组织级治理限制，请稍后重试或调整执行策略',
          data: {
            governanceBlocked: true,
            governanceDecision: {
              decisionCode: 'BLOCKED_QUOTA_EXCEEDED',
              summary: '组织配额不足：TASK_CONCURRENCY 剩余 0，无法再预留 1',
              sourceUrls: ['https://ops.example.com/quota/task-concurrency'],
            },
          },
        },
      },
    }

    const message = buildGovernanceActionFailureMessage(error, '创建任务失败', '请稍后重试')

    expect(message).toContain('组织配额或并发额度暂时不足')
    expect(message).toContain('请稍后重试，或联系操作者查看当前治理运行摘要')
    expect(message).toContain('来源：https://ops.example.com/quota/task-concurrency')
  })
})
