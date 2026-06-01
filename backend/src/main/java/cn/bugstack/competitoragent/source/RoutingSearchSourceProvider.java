package cn.bugstack.competitoragent.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索补源路由器。
 * 统一聚合多个搜索补源实现，避免某一种 provider 命中了单一入口后就过早停止，
 * 导致候选来源过窄、视角单一。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RoutingSearchSourceProvider implements SearchSourceProvider {

    private final SearchProviderProperties properties;
    private final SerpApiSearchSourceProvider serpApiSearchSourceProvider;
    private final BrowserPreviewSearchSourceProvider browserPreviewProvider;
    private final HttpSearchSourceProvider httpSearchSourceProvider;
    private final SourceCandidateRanker sourceCandidateRanker;

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        List<SourceCandidate> mergedCandidates = new ArrayList<>();

        List<SourceCandidate> serpApiCandidates = serpApiSearchSourceProvider.search(competitorName, requestedScopes);
        if (!serpApiCandidates.isEmpty()) {
            mergedCandidates.addAll(serpApiCandidates);
        }

        if (properties.isBrowserPreviewEnabled()) {
            List<SourceCandidate> previewCandidates = browserPreviewProvider.search(competitorName, requestedScopes);
            if (!previewCandidates.isEmpty()) {
                mergedCandidates.addAll(previewCandidates);
            }
        }

        List<SourceCandidate> httpCandidates = httpSearchSourceProvider.search(competitorName, requestedScopes);
        if (!httpCandidates.isEmpty()) {
            mergedCandidates.addAll(httpCandidates);
        }

        return sourceCandidateRanker.rankAndDeduplicate(mergedCandidates);
    }
}
