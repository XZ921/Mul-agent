package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.knowledge.KnowledgeDocumentQueryService;
import cn.bugstack.competitoragent.knowledge.KnowledgeDomainService;
import cn.bugstack.competitoragent.knowledge.KnowledgeIngestionService;
import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.model.dto.KnowledgeIngestionRequest;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织级知识接入控制器。
 * <p>
 * Task 5.2.d 只提供最小可用入口：
 * 1. 查询可选知识域；
 * 2. 提交资料接入；
 * 3. 查看某个知识域下已经形成的接入摘要。
 * 不在这里提前演进成独立后台或连接器运行时管理台。
 */
@Tag(name = "Knowledge", description = "组织级知识接入与摘要查询")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeDomainService knowledgeDomainService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;

    @GetMapping("/domains")
    @Operation(summary = "List active knowledge domains")
    public ApiResponse<List<KnowledgeDomain>> listDomains() {
        return ApiResponse.success(knowledgeDomainService.listActiveDomains());
    }

    @PostMapping("/ingest")
    @Operation(summary = "Ingest organization knowledge source")
    public ApiResponse<KnowledgeDocumentResponse> ingest(@Valid @RequestBody KnowledgeIngestionRequest request) {
        KnowledgeDocument document = knowledgeIngestionService.ingest(request);
        return ApiResponse.success(
                knowledgeDocumentQueryService.toResponse(document),
                "Knowledge source ingested"
        );
    }

    @GetMapping("/domains/{domainKey}/documents")
    @Operation(summary = "List ingested documents by knowledge domain")
    public ApiResponse<List<KnowledgeDocumentResponse>> listDomainDocuments(@PathVariable String domainKey) {
        return ApiResponse.success(knowledgeDocumentQueryService.listByDomainKey(domainKey));
    }
}
