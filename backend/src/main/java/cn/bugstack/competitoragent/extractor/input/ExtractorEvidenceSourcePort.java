package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;

import java.util.List;

public interface ExtractorEvidenceSourcePort {

    List<ExtractorEvidenceInput> load(AgentContext context);
}
