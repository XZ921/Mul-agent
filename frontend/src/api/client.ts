import axios from 'axios'
import type {
  AgentLog,
  AnalysisSchema,
  ApiResponse,
  ConversationMessageRequest,
  ConversationResponse,
  CreateTaskRequest,
  EvidenceInfo,
  GovernanceRuntimeSummaryInfo,
  KnowledgeDocumentInfo,
  KnowledgeDomainInfo,
  KnowledgeIngestionPayload,
  ReportExportInfo,
  TaskReplayResponse,
  ReportInfo,
  TaskInfo,
  TaskListPageData,
  TaskNodeInfo,
  TaskPlanPreviewContract,
} from '../types'

const api = axios.create({
  baseURL: '/api',
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const message = error.response?.data?.message || error.message || '网络请求异常'
    console.error('[API Error]', message)
    return Promise.reject(error)
  },
)

export async function createTask(data: CreateTaskRequest) {
  return api.post('/task/create', data) as Promise<ApiResponse<TaskInfo>>
}

export async function previewWorkflow(data: CreateTaskRequest) {
  return api.post('/task/preview', data) as Promise<ApiResponse<TaskPlanPreviewContract>>
}

export async function listTasks(status?: string, pageNum = 1, pageSize = 10) {
  const params = {
    pageNum,
    pageSize,
    ...(status ? { status } : {}),
  }
  return api.get('/task/list', { params }) as Promise<ApiResponse<TaskListPageData>>
}

export async function getTask(id: number) {
  return api.get(`/task/${id}`) as Promise<ApiResponse<TaskInfo>>
}

export async function sendConversationMessage(data: ConversationMessageRequest) {
  return api.post('/conversation/message', data) as Promise<ApiResponse<ConversationResponse>>
}

export async function getTaskNodes(id: number) {
  return api.get(`/task/${id}/nodes`) as Promise<ApiResponse<TaskNodeInfo[]>>
}

export async function getTaskReplay(id: number) {
  return api.get(`/task/${id}/replay`) as Promise<ApiResponse<TaskReplayResponse>>
}

export async function executeTask(id: number) {
  return api.post(`/task/${id}/execute`) as Promise<ApiResponse<string>>
}

export async function resumeTask(id: number) {
  return api.post(`/task/${id}/resume`) as Promise<ApiResponse<string>>
}

export async function stopTask(id: number) {
  return api.post(`/task/${id}/stop`) as Promise<ApiResponse<string>>
}

export async function retryTask(id: number) {
  return api.post(`/task/${id}/retry`) as Promise<ApiResponse<string>>
}

export async function rerunTaskNode(id: number, nodeName: string) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/rerun`) as Promise<ApiResponse<string>>
}

export async function updateTaskNodeConfigAndRerun(
  id: number,
  nodeName: string,
  nodeConfig: string,
  changeSummary?: string,
) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/config-rerun`, {
    nodeConfig,
    changeSummary,
  }) as Promise<ApiResponse<string>>
}

export async function pauseTaskNode(id: number, nodeName: string) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/pause`) as Promise<ApiResponse<string>>
}

export async function resumeTaskNode(id: number, nodeName: string) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/resume`) as Promise<ApiResponse<string>>
}

export async function skipTaskNode(id: number, nodeName: string) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/skip`) as Promise<ApiResponse<string>>
}

export async function terminateTaskNode(id: number, nodeName: string) {
  return api.post(`/task/${id}/nodes/${encodeURIComponent(nodeName)}/terminate`) as Promise<ApiResponse<string>>
}

export async function deleteTask(id: number) {
  return api.delete(`/task/${id}`) as Promise<ApiResponse<string>>
}

export async function getReport(taskId: number) {
  return api.get(`/report/${taskId}`) as Promise<ApiResponse<ReportInfo>>
}

export async function listReportEvidences(
  taskId: number,
  filters?: { competitorName?: string; sourceType?: string; discoveryMethod?: string },
) {
  return api.get(`/report/${taskId}/evidences`, { params: filters }) as Promise<ApiResponse<EvidenceInfo[]>>
}

export async function listTaskExports(taskId: number) {
  return api.get(`/delivery/task/${taskId}/exports`) as Promise<ApiResponse<ReportExportInfo[]>>
}

export async function getGovernanceSummary(organizationKey?: string) {
  return api.get('/governance/runtime-summary', {
    params: organizationKey ? { organizationKey } : {},
  }) as Promise<ApiResponse<GovernanceRuntimeSummaryInfo>>
}

export function getExportUrl(taskId: number) {
  return `/api/report/${taskId}/export`
}

export function getHtmlExportUrl(taskId: number) {
  return `/api/report/${taskId}/export/html`
}

export async function getAgentLogs(taskId: number) {
  return api.get(`/agent-log/task/${taskId}`) as Promise<ApiResponse<AgentLog[]>>
}

export async function getAgentLogsByType(taskId: number, agentType: string) {
  return api.get(`/agent-log/task/${taskId}/agent/${agentType}`) as Promise<ApiResponse<AgentLog[]>>
}

export async function getAgentLogDetail(logId: number) {
  return api.get(`/agent-log/${logId}`) as Promise<ApiResponse<AgentLog>>
}

export async function listSchemas() {
  return api.get('/schema/list') as Promise<ApiResponse<AnalysisSchema[]>>
}

export async function listKnowledgeDomains() {
  return api.get('/knowledge/domains') as Promise<ApiResponse<KnowledgeDomainInfo[]>>
}

export async function listKnowledgeDomainDocuments(domainKey: string) {
  return api.get(`/knowledge/domains/${encodeURIComponent(domainKey)}/documents`) as Promise<ApiResponse<KnowledgeDocumentInfo[]>>
}

export async function ingestKnowledgeSource(data: KnowledgeIngestionPayload) {
  return api.post('/knowledge/ingest', data) as Promise<ApiResponse<KnowledgeDocumentInfo>>
}

/**
 * 统一生成任务 SSE 订阅地址。
 * 后端已经把事件流路径下发到任务详情里，这里优先复用后端返回值；若首帧快照尚未到达，则回退到约定路径。
 */
export function getTaskEventStreamUrl(taskId: number, eventStreamPath?: string | null) {
  if (eventStreamPath && eventStreamPath.trim()) {
    return eventStreamPath
  }
  return `/api/task/${taskId}/events`
}
