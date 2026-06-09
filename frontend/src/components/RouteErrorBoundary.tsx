import { Alert, Button, Space, Typography } from 'antd'
import { Component, type ErrorInfo, type ReactNode } from 'react'

const { Paragraph, Text } = Typography

type Props = {
  children: ReactNode
  pageLabel?: string
}

type State = {
  hasError: boolean
}

/**
 * React 18 中任一路由组件在渲染阶段抛出未捕获异常时，整棵 React 树都会被卸载。
 * 这里在页面边界兜住异常，把白屏降级成可见错误提示，同时保留控制台堆栈便于继续排查。
 */
export default class RouteErrorBoundary extends Component<Props, State> {
  state: State = {
    hasError: false,
  }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('[RouteErrorBoundary]', error, errorInfo)
  }

  private handleRetry = () => {
    this.setState({ hasError: false })
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children
    }

    const pageLabel = this.props.pageLabel || '当前页面'
    return (
      <Alert
        type="error"
        showIcon
        message="页面渲染失败"
        description={(
          <Space direction="vertical" size={8}>
            <Paragraph style={{ marginBottom: 0 }}>
              {`${pageLabel}在渲染过程中发生异常，已自动阻止整页白屏。`}
            </Paragraph>
            <Text type="secondary">
              请先刷新页面或返回上一页；如果再次出现，请打开浏览器 DevTools Console 查看具体异常堆栈。
            </Text>
            <div>
              <Button onClick={this.handleRetry}>重试渲染</Button>
            </div>
          </Space>
        )}
      />
    )
  }
}
