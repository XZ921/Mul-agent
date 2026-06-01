package cn.bugstack.competitoragent.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 浏览器搜索配置告警器。
 * 避免 search.browser.enabled 关闭时系统静默降级，用户却以为浏览器搜索正在工作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchBrowserConfigurationGuard implements ApplicationRunner {

    private final SearchProperties searchProperties;
    private final SearchBrowserProperties searchBrowserProperties;

    @Override
    public void run(ApplicationArguments args) {
        String mode = normalizeMode(searchProperties.getMode());
        if (!searchBrowserProperties.isEnabled()) {
            if ("BROWSER_ONLY".equals(mode)) {
                log.error("检测到 search.browser.enabled=false 且 search.mode=BROWSER_ONLY。"
                        + " 浏览器搜索链路将完全不可用，请在 application.yml 中显式设置 search.browser.enabled: true。");
                return;
            }
            log.warn("检测到 search.browser.enabled=false。当前 search.mode={}，系统会静默回退到 HTTP/规划候选。"
                    + " 如果希望执行真实浏览器搜索并展示浏览器窗口，请在 application.yml 中设置 search.browser.enabled: true。", mode);
            return;
        }
        log.info("浏览器搜索已启用: engine={}, fallbackEngines={}, mode={}, verifyResultPage={}, maxOpenResultPages={}",
                searchBrowserProperties.getEngine(),
                searchBrowserProperties.getFallbackEngines(),
                mode,
                searchBrowserProperties.isVerifyResultPage(),
                searchBrowserProperties.getMaxOpenResultPages());
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "HYBRID";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }
}
