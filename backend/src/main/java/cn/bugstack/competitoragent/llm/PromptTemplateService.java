package cn.bugstack.competitoragent.llm;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PromptTemplateService {

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    private static final Map<String, String> DEFAULT_TEMPLATES = Map.of(
            "writer", """
                    You are a professional business report writer.
                    # Task
                    {taskName}

                    # Subject Product
                    {subjectProduct}

                    # Analysis Result
                    {analysisResult}

                    # Current Report Draft
                    {currentReport}

                    # Revision Mode
                    {revisionMode}

                    # Revision Plan
                    {revisionPlan}

                    # Evidence
                    {evidenceList}

                    Write a complete Markdown competitor analysis report.
                    If revisionMode is true, revise the current report draft, prioritize the revision plan, and fix the identified issues.
                    """,
            "reviewer", """
                    You are a strict quality reviewer.
                    # Review Mode
                    {reviewMode}

                    # Report
                    {reportContent}

                    # Evidence
                    {evidenceList}

                    Return JSON only with fields:
                    {
                      "score": 85,
                      "passed": true,
                      "issues": [
                        {
                          "type": "MISSING_EVIDENCE",
                          "section": "section name",
                          "severity": "ERROR",
                          "suggestion": "fix suggestion"
                        }
                      ],
                      "summary": "short summary"
                    }

                    If the report needs revision, set passed=false and provide actionable issues.
                    """,
            "extractor", """
                    You are a competitor knowledge extraction expert.
                    You must return JSON only.

                    # Competitor
                    {competitorName}

                    # Evidence Catalog
                    {evidenceCatalog}

                    # Collected Content
                    {collectedContent}

                    Extract a structured competitor profile with this schema:
                    {
                      "officialUrl": "https://...",
                      "summary": "short summary",
                      "positioning": "market positioning",
                      "targetUsers": ["segment A"],
                      "coreFeatures": [
                        {
                          "name": "feature name",
                          "description": "feature description",
                          "evidenceIds": ["T0001-COLLECT-001"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "pricing": {
                        "model": "pricing model",
                        "plans": ["Free", "Pro"],
                        "evidenceIds": ["T0001-COLLECT-002"],
                        "sourceUrls": ["https://..."]
                      },
                      "strengths": [
                        {
                          "point": "strength point",
                          "evidenceIds": ["T0001-COLLECT-003"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "weaknesses": [
                        {
                          "point": "weakness point",
                          "evidenceIds": ["T0001-COLLECT-004"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "sources": [
                        {
                          "evidenceId": "T0001-COLLECT-001",
                          "title": "source title",
                          "url": "https://..."
                        }
                      ],
                      "sourceUrls": ["https://..."]
                    }

                    Rules:
                    1. sourceUrls is required and must contain only URLs that appear in the evidence catalog.
                    2. For any structured item you can support, include evidenceIds and sourceUrls.
                    3. Do not invent unavailable pricing, features, or target users.
                    4. If a field is uncertain, leave it empty instead of guessing.
                    """
    );

    @PostConstruct
    public void init() {
        for (String name : List.of("writer", "reviewer", "extractor")) {
            try {
                ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
                if (resource.exists()) {
                    templates.put(name, resource.getContentAsString(StandardCharsets.UTF_8));
                } else {
                    templates.put(name, DEFAULT_TEMPLATES.get(name));
                }
            } catch (IOException e) {
                templates.put(name, DEFAULT_TEMPLATES.get(name));
                log.warn("load prompt template {} failed, fallback to default", name, e);
            }
        }
    }

    public String getTemplate(String templateName) {
        String template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template name: " + templateName);
        }
        return template;
    }

    public String render(String templateName, Map<String, String> variables) {
        String template = getTemplate(templateName);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
