package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三轮先把“repository 读证据”收口到端口适配器，
 * 后续 replay / cache 只允许替换这里，不允许把正文读取逻辑重新塞回 Agent。
 */
@Component
@RequiredArgsConstructor
public class RepositoryExtractorEvidenceSourcePort implements ExtractorEvidenceSourcePort {

    private final EvidenceSourceRepository evidenceSourceRepository;
    private final ExtractorEvidenceInputAssembler extractorEvidenceInputAssembler;

    @Override
    public List<ExtractorEvidenceInput> load(AgentContext context) {
        List<EvidenceSource> evidences = evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(
                context == null ? null : context.getTaskId());
        List<ExtractorEvidenceInput> inputs = new ArrayList<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence != null) {
                inputs.add(extractorEvidenceInputAssembler.fromEvidenceSource(evidence));
            }
        }
        return inputs;
    }
}
