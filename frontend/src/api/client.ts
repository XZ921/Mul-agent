import axios from 'axios'
import type {
  AgentLog,
  AnalysisSchema,
  ApiResponse,
  CreateTaskRequest,
  ReportInfo,
  TaskInfo,
  TaskNodeInfo,
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
  return api.post('/task/preview', data) as Promise<ApiResponse<TaskNodeInfo[]>>
}

export async function listTasks(status?: string) {
  return api.get('/task/list', { params: status ? { status } : {} }) as Promise<ApiResponse<TaskInfo[]>>
}

export async function getTask(id: number) {
  return api.get(`/task/${id}`) as Promise<ApiResponse<TaskInfo>>
}

export async function getTaskNodes(id: number) {
  return api.get(`/task/${id}/nodes`) as Promise<ApiResponse<TaskNodeInfo[]>>
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

export async function deleteTask(id: number) {
  return api.delete(`/task/${id}`) as Promise<ApiResponse<string>>
}

export async function getReport(taskId: number) {
  return api.get(`/report/${taskId}`) as Promise<ApiResponse<ReportInfo>>
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
