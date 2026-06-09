import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App as AntdApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import { antAppTheme, applyThemeCssVariables } from './styles/theme'
import { registerAppMessage } from './utils/appMessage'
import './styles/index.css'

applyThemeCssVariables()

function AntdAppBridge() {
  const { message } = AntdApp.useApp()
  registerAppMessage(message)
  return <App />
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={antAppTheme}>
      <AntdApp>
        <BrowserRouter
          future={{
            v7_startTransition: true,
            v7_relativeSplatPath: true,
          }}
        >
          <AntdAppBridge />
        </BrowserRouter>
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
)
