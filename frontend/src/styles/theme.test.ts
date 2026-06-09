import { afterEach, describe, expect, it } from 'vitest'
import { applyThemeCssVariables, appThemeTokens } from './theme'

describe('theme conversation tokens', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('style')
  })

  it('applies dedicated conversation design tokens to css variables', () => {
    applyThemeCssVariables()

    const rootStyle = document.documentElement.style
    expect(rootStyle.getPropertyValue('--app-conversation-context-bg-start')).toBe(
      appThemeTokens.colorConversationContextBgStart,
    )
    expect(rootStyle.getPropertyValue('--app-conversation-context-bg-end')).toBe(
      appThemeTokens.colorConversationContextBgEnd,
    )
    expect(rootStyle.getPropertyValue('--app-conversation-detail-bg')).toBe(
      appThemeTokens.colorConversationDetailBg,
    )
    expect(rootStyle.getPropertyValue('--app-conversation-message-user-end')).toBe(
      appThemeTokens.colorConversationMessageUserBgEnd,
    )
    expect(rootStyle.getPropertyValue('--app-conversation-message-assistant-end')).toBe(
      appThemeTokens.colorConversationMessageAssistantBgEnd,
    )
    expect(rootStyle.getPropertyValue('--app-conversation-composer-shadow')).toBe(
      appThemeTokens.shadowConversationComposer,
    )
  })
})
