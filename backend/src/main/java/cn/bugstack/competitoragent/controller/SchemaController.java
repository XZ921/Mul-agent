package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.SchemaRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisSchema;
import cn.bugstack.competitoragent.schema.SchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Schema 管理", description = "竞品分析模板的增删改查")
@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaService schemaService;

    @GetMapping("/list")
    @Operation(summary = "获取所有分析模板")
    public ApiResponse<List<AnalysisSchema>> listSchemas() {
        return ApiResponse.success(schemaService.listSchemas());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模板详情")
    public ApiResponse<AnalysisSchema> getSchema(
            @Parameter(description = "模板 ID", example = "1")
            @PathVariable Long id) {
        return ApiResponse.success(schemaService.getSchema(id));
    }

    @PostMapping("/create")
    @Operation(summary = "创建自定义分析模板")
    public ApiResponse<AnalysisSchema> createSchema(@Valid @RequestBody SchemaRequest request) {
        return ApiResponse.success(schemaService.createSchema(request), "模板创建成功");
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分析模板")
    public ApiResponse<AnalysisSchema> updateSchema(
            @Parameter(description = "模板 ID", example = "1")
            @PathVariable Long id,
            @Valid @RequestBody SchemaRequest request) {
        return ApiResponse.success(schemaService.updateSchema(id, request), "模板更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除分析模板")
    public ApiResponse<String> deleteSchema(
            @Parameter(description = "模板 ID", example = "1")
            @PathVariable Long id) {
        schemaService.deleteSchema(id);
        return ApiResponse.success("模板已删除");
    }
}
