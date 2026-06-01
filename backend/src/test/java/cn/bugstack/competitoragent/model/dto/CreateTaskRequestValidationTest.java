package cn.bugstack.competitoragent.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTaskRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldRejectUnsafeCompetitorUrls() {
        CreateTaskRequest request = validRequest();
        request.setCompetitorUrls(List.of("file:///etc/passwd"));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void shouldAcceptHttpOrHttpsCompetitorUrls() {
        CreateTaskRequest request = validRequest();
        request.setCompetitorUrls(List.of("https://www.notion.so", "http://example.com/docs"));

        assertTrue(validator.validate(request).isEmpty());
    }

    private CreateTaskRequest validRequest() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("AI 竞品分析");
        request.setSubjectProduct("企业级知识库");
        request.setCompetitorNames(List.of("Notion AI"));
        return request;
    }
}
