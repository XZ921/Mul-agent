import { lazy, Suspense } from 'react'
import { Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu, Spin } from 'antd'
import {
  FileSearchOutlined,
  MessageOutlined,
  PlusCircleOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import RouteErrorBoundary from './components/RouteErrorBoundary'

const { Header, Content } = Layout
const TaskListPage = lazy(() => import('./pages/TaskListPage'))
const TaskCreatePage = lazy(() => import('./pages/TaskCreatePage'))
const TaskDetailPage = lazy(() => import('./pages/TaskDetailPage'))
const ReportPage = lazy(() => import('./pages/ReportPage'))
const ConversationPage = lazy(() => import('./pages/ConversationPage'))

const menuItems = [
  { key: '/', icon: <UnorderedListOutlined />, label: <Link to="/">任务列表</Link> },
  { key: '/task/new', icon: <PlusCircleOutlined />, label: <Link to="/task/new">新建任务</Link> },
  { key: '/conversation', icon: <MessageOutlined />, label: <Link to="/conversation">统一对话</Link> },
]

export default function App() {
  const location = useLocation()
  const selectedKey = location.pathname === '/task/new'
    ? '/task/new'
    : location.pathname === '/conversation'
      ? '/conversation'
      : '/'

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <Link to="/" className="brand">
          <FileSearchOutlined />
          <span>竞品分析智能体</span>
        </Link>
        <span className="subtitle">多智能体协作 · 过程透明 · 结论可溯源</span>
        <Menu mode="horizontal" selectedKeys={[selectedKey]} items={menuItems} className="top-menu" />
      </Header>
      <Content className="app-content">
        <Suspense
          fallback={(
            <div style={{ display: 'flex', justifyContent: 'center', padding: '64px 16px' }}>
              <div style={{ textAlign: 'center' }}>
                <Spin size="large" />
                <div style={{ marginTop: 12 }}>页面加载中，请稍候...</div>
              </div>
            </div>
          )}
        >
          <Routes>
            <Route
              path="/"
              element={(
                <RouteErrorBoundary pageLabel="任务列表">
                  <TaskListPage />
                </RouteErrorBoundary>
              )}
            />
            <Route
              path="/task/new"
              element={(
                <RouteErrorBoundary pageLabel="新建任务">
                  <TaskCreatePage />
                </RouteErrorBoundary>
              )}
            />
            <Route
              path="/task/:id"
              element={(
                <RouteErrorBoundary pageLabel="任务详情">
                  <TaskDetailPage />
                </RouteErrorBoundary>
              )}
            />
            <Route
              path="/task/:id/report"
              element={(
                <RouteErrorBoundary pageLabel="报告页面">
                  <ReportPage />
                </RouteErrorBoundary>
              )}
            />
            <Route
              path="/conversation"
              element={(
                <RouteErrorBoundary pageLabel="统一对话入口">
                  <ConversationPage />
                </RouteErrorBoundary>
              )}
            />
          </Routes>
        </Suspense>
      </Content>
    </Layout>
  )
}
