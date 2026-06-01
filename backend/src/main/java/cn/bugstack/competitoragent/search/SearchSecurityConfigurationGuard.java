package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 搜索链路安全配置告警器。
 * 启动时拒绝不安全的搜索引擎地址和 SerpAPI endpoint。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchSecurityConfigurationGuard implements ApplicationRunner {

    private final SearchEngineProperties searchEngineProperties;
    private final SerpApiProperties serpApiProperties;

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, SearchEngineProperties.EngineConfig> entry : searchEngineProperties.entrySet()) {
            SearchEngineProperties.EngineConfig config = entry.getValue();
            if (config == null || !StringUtils.hasText(config.getBaseUrl())) {
                continue;
            }
            if (!UrlSecurityUtils.isHttpsUrl(config.getBaseUrl())) {
                throw new BusinessException(ResultCode.PARAM_INVALID,
                        "search.engines." + entry.getKey() + ".base-url 必须使用 https URL");
            }
        }
        if (StringUtils.hasText(serpApiProperties.getEndpoint())
                && !UrlSecurityUtils.isHttpsUrl(serpApiProperties.getEndpoint())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "serpapi.endpoint 必须使用 https URL");
        }
        log.info("搜索安全配置校验通过: engineCount={}, serpapiConfigured={}",
                searchEngineProperties.size(),
                StringUtils.hasText(serpApiProperties.getApiKey()));
    }
}
