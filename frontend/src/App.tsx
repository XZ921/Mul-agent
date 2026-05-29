import { Routes, Route, Link, useLocation } from 'react-router-dom'
import { Layout, Menu } from 'antd'
import {
  FileSearchOutlined,
  PlusCircleOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import TaskListPage from './pages/TaskListPage'
import TaskCreatePage from './pages/TaskCreatePage'
import TaskDetailPage from './pages/TaskDetailPage'
import ReportPage from './pages/ReportPage'

const { Header, Content } = Layout

const menuItems = [
  { key: '/', icon: <UnorderedListOutlined />, label: <Link to="/">任务列表</Link> },
  { key: '/task/new', icon: <PlusCircleOutlined />, label: <Link to="/task/new">新建任务</Link> },
]

export default function App() {
  const location = useLocation()
  const selectedKey = location.pathname === '/task/new' ? '/task/new' : '/'

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <Link to="/" className="brand">
          <FileSearchOutlined />
          <span>竞品分析 Agent</span>
        </Link>
        <span className="subtitle">多 Agent 协作 · 过程透明 · 结论可溯源</span>
        <Menu
          mode="horizontal"
          selectedKeys={[selectedKey]}
          items={menuItems}
          className="top-menu"
        />
      </Header>
      <Content className="app-content">
        <Routes>
          <Route path="/" element={<TaskListPage />} />
          <Route path="/task/new" element={<TaskCreatePage />} />
          <Route path="/task/:id" element={<TaskDetailPage />} />
          <Route path="/task/:id/report" element={<ReportPage />} />
        </Routes>
      </Content>
    </Layout>
  )
}
