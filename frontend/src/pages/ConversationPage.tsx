import { Alert } from 'antd'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { sendConversationMessage } from '../api/client'
import ConversationWorkspace from '../components/conversation/ConversationWorkspace'
import KnowledgeSourceIntakeCard from '../components/task-create/KnowledgeSourceIntakeCard'
import type {
  ConversationActionConfirmationRequest,
  ConversationMessageRequest,
  ConversationResponse,
} from '../types'
import { appMessage } from '../utils/appMessage'
import {
  buildConversationMessageRequest,
  readConversationEntryContext,
} from '../utils/conversationPresentation'
import { buildGovernanceActionFailureMessage } from '../utils/governancePresentation'
import type { ConversationTimelineItem } from '../components/conversation/ConversationMessageList'

export default function ConversationPage() {
  const [searchParams] = useSearchParams()
  const context = useMemo(
    () => readConversationEntryContext(searchParams),
    [searchParams],
  )
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ConversationTimelineItem[]>([])
  const [submitting, setSubmitting] = useState(false)

  /**
   * 对话消息统一在页面层落库到时间线，
   * 这样无论是文本解释、动作预览还是证据卡片，都能沿用同一条消息流承载。
   */
  const handleSend = async (message: string) => {
    return submitConversationRequest({
      message,
      executeConfirmedAction: false,
      confirmationRequest: null,
    })
  }

  /**
   * 高风险动作确认仍然复用统一对话入口提交，
   * 这样预览、确认和执行结果可以落在同一条时间线上，而不是跳回其他零散入口。
   */
  const handleConfirmAction = async (confirmationRequest: ConversationActionConfirmationRequest) => {
    return submitConversationRequest({
      message: '确认执行这个动作',
      executeConfirmedAction: true,
      confirmationRequest,
    })
  }

  const submitConversationRequest = async ({
    message,
    executeConfirmedAction,
    confirmationRequest,
  }: {
    message: string
    executeConfirmedAction: boolean
    confirmationRequest: ConversationActionConfirmationRequest | null
  }) => {
    const normalized = message.trim()
    if (!normalized || submitting) {
      return false
    }

    const userMessage: ConversationTimelineItem = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: normalized,
    }
    setMessages((current) => [...current, userMessage])
    setSubmitting(true)

    try {
      const requestPayload: ConversationMessageRequest = buildConversationMessageRequest(
        context,
        sessionId,
        normalized,
      )
      if (executeConfirmedAction) {
        requestPayload.executeConfirmedAction = true
        requestPayload.confirmationRequest = confirmationRequest
      }
      const response = await sendConversationMessage(requestPayload)
      const assistantPayload = response.data
      setSessionId(assistantPayload.sessionId ?? sessionId)
      appendAssistantMessage(setMessages, assistantPayload)
      return true
    } catch (error) {
      const readableMessage = buildGovernanceActionFailureMessage(
        error,
        '统一对话入口暂时不可用',
        '请稍后重试或返回原页面继续处理。',
      )
      appMessage.error(readableMessage)
      appendAssistantMessage(setMessages, {
        answer: readableMessage,
        sourceUrls: [],
        retrievalEvidences: [],
      })
      return true
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <div className="page-header">
        <h2>统一对话页</h2>
        <p>先说明当前上下文与你可以继续提问的方向，再逐步展开解释、建议和证据回指。</p>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="统一入口会先解释当前上下文，再逐步展开动作建议与证据回指。"
      />

      <ConversationWorkspace
        context={context}
        messages={messages}
        submitting={submitting}
        onSend={handleSend}
        onConfirmAction={handleConfirmAction}
      />

      <div style={{ marginTop: 16 }}>
        <KnowledgeSourceIntakeCard taskId={context.taskId} title="资料接入" />
      </div>
    </div>
  )
}

function appendAssistantMessage(
  setMessages: React.Dispatch<React.SetStateAction<ConversationTimelineItem[]>>,
  response: Partial<ConversationResponse>,
) {
  setMessages((current) => [
    ...current,
    {
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: response.answer || '当前没有更多说明。',
      response: {
        answer: response.answer || '当前没有更多说明。',
        currentStage: response.currentStage ?? null,
        mode: response.mode ?? null,
        sessionId: response.sessionId ?? null,
        statusSummary: response.statusSummary ?? null,
        taskRagContextSummary: response.taskRagContextSummary ?? null,
        sourceUrls: response.sourceUrls || [],
        intentDecision: response.intentDecision ?? null,
        formDraft: response.formDraft ?? null,
        taskActionPreview: response.taskActionPreview ?? null,
        taskActionExecution: response.taskActionExecution ?? null,
        clarification: response.clarification ?? null,
        retrievalEvidences: response.retrievalEvidences || [],
      },
    },
  ])
}
