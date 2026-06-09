import type { ThemeConfig } from 'antd'

export const appThemeTokens = {
  colorPrimary: '#1f5eff',
  colorSuccess: '#13795b',
  colorWarning: '#b45309',
  colorError: '#c2410c',
  colorInfo: '#1f5eff',
  colorBgLayout: '#f3f6fb',
  colorBgContainer: '#ffffff',
  colorBgElevated: '#ffffff',
  colorBorder: '#dbe4f0',
  colorBorderSecondary: '#e7edf5',
  colorText: '#20242a',
  colorTextSecondary: '#667085',
  colorTextTertiary: '#8a94a6',
  colorFillSecondary: '#edf3fb',
  colorFillTertiary: '#f7f9fc',
  colorBrandStrong: '#143d69',
  colorBrandMuted: '#5d6b82',
  colorConversationContextBgStart: '#f7f9fc',
  colorConversationContextBgEnd: '#ffffff',
  colorConversationContextBorder: '#dbe4f0',
  colorConversationDetailBg: '#f7f9fc',
  colorConversationDetailBorder: '#dbe4f0',
  colorConversationMessageUserBgStart: '#ffffff',
  colorConversationMessageUserBgEnd: '#edf3fb',
  colorConversationMessageAssistantBgStart: '#ffffff',
  colorConversationMessageAssistantBgEnd: '#f7f9fc',
  colorConversationComposerBorder: '#dbe4f0',
  shadowConversationComposer: '0 12px 24px rgba(25, 52, 84, 0.08)',
  borderRadius: 10,
  borderRadiusLG: 14,
  fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, sans-serif',
} as const

export const antAppTheme: ThemeConfig = {
  token: {
    colorPrimary: appThemeTokens.colorPrimary,
    colorSuccess: appThemeTokens.colorSuccess,
    colorWarning: appThemeTokens.colorWarning,
    colorError: appThemeTokens.colorError,
    colorInfo: appThemeTokens.colorInfo,
    colorBgLayout: appThemeTokens.colorBgLayout,
    colorBgContainer: appThemeTokens.colorBgContainer,
    colorBgElevated: appThemeTokens.colorBgElevated,
    colorBorder: appThemeTokens.colorBorder,
    colorBorderSecondary: appThemeTokens.colorBorderSecondary,
    colorText: appThemeTokens.colorText,
    colorTextSecondary: appThemeTokens.colorTextSecondary,
    colorTextTertiary: appThemeTokens.colorTextTertiary,
    colorFillSecondary: appThemeTokens.colorFillSecondary,
    colorFillTertiary: appThemeTokens.colorFillTertiary,
    borderRadius: appThemeTokens.borderRadius,
    borderRadiusLG: appThemeTokens.borderRadiusLG,
    fontFamily: appThemeTokens.fontFamily,
  },
  components: {
    Card: {
      borderRadiusLG: appThemeTokens.borderRadiusLG,
    },
    Button: {
      borderRadius: appThemeTokens.borderRadius,
      controlHeight: 36,
    },
    Collapse: {
      borderRadiusLG: appThemeTokens.borderRadiusLG,
    },
    Drawer: {
      colorBgElevated: appThemeTokens.colorBgElevated,
    },
    Tag: {
      borderRadiusSM: 999,
    },
    Tabs: {
      inkBarColor: appThemeTokens.colorPrimary,
      itemSelectedColor: appThemeTokens.colorPrimary,
    },
  },
}

export function applyThemeCssVariables() {
  const root = document.documentElement
  root.style.setProperty('--app-color-primary', appThemeTokens.colorPrimary)
  root.style.setProperty('--app-color-success', appThemeTokens.colorSuccess)
  root.style.setProperty('--app-color-warning', appThemeTokens.colorWarning)
  root.style.setProperty('--app-color-error', appThemeTokens.colorError)
  root.style.setProperty('--app-color-bg-page', appThemeTokens.colorBgLayout)
  root.style.setProperty('--app-color-bg-card', appThemeTokens.colorBgContainer)
  root.style.setProperty('--app-color-border', appThemeTokens.colorBorderSecondary)
  root.style.setProperty('--app-color-fill-secondary', appThemeTokens.colorFillSecondary)
  root.style.setProperty('--app-color-fill-tertiary', appThemeTokens.colorFillTertiary)
  root.style.setProperty('--app-color-text', appThemeTokens.colorText)
  root.style.setProperty('--app-color-text-secondary', appThemeTokens.colorTextSecondary)
  root.style.setProperty('--app-color-text-tertiary', appThemeTokens.colorTextTertiary)
  root.style.setProperty('--app-color-brand-strong', appThemeTokens.colorBrandStrong)
  root.style.setProperty('--app-color-brand-muted', appThemeTokens.colorBrandMuted)
  root.style.setProperty('--app-conversation-context-bg-start', appThemeTokens.colorConversationContextBgStart)
  root.style.setProperty('--app-conversation-context-bg-end', appThemeTokens.colorConversationContextBgEnd)
  root.style.setProperty('--app-conversation-context-border', appThemeTokens.colorConversationContextBorder)
  root.style.setProperty('--app-conversation-detail-bg', appThemeTokens.colorConversationDetailBg)
  root.style.setProperty('--app-conversation-detail-border', appThemeTokens.colorConversationDetailBorder)
  root.style.setProperty('--app-conversation-message-user-start', appThemeTokens.colorConversationMessageUserBgStart)
  root.style.setProperty('--app-conversation-message-user-end', appThemeTokens.colorConversationMessageUserBgEnd)
  root.style.setProperty('--app-conversation-message-assistant-start', appThemeTokens.colorConversationMessageAssistantBgStart)
  root.style.setProperty('--app-conversation-message-assistant-end', appThemeTokens.colorConversationMessageAssistantBgEnd)
  root.style.setProperty('--app-conversation-composer-border', appThemeTokens.colorConversationComposerBorder)
  root.style.setProperty('--app-conversation-composer-shadow', appThemeTokens.shadowConversationComposer)
  root.style.setProperty('--app-radius-sm', `${appThemeTokens.borderRadius}px`)
  root.style.setProperty('--app-radius-lg', `${appThemeTokens.borderRadiusLG}px`)
  root.style.setProperty('--app-font-family', appThemeTokens.fontFamily)
}
