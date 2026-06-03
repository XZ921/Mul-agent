package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisItem;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisSection;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceReference;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReportDiagnosisInfo;
import cn.bugstack.competitoragent.report.EvidenceQueryService;
import cn.bugstack.competitoragent.report.ReportService;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportControllerTest {

    private final ReportService reportService = mock(ReportService.class);
    private final EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService, evidenceQueryService)).build();
    }

    @Test
    void shouldReturnNestedReportDiagnosisModelWithoutFrontendAssembly() throws Exception {
        ReportResponse response = ReportResponse.builder()
                .id(1L)
                .taskId(99L)
                .title("企业级竞品分析")
                .content("# Report")
                .reportDiagnosis(ReportDiagnosisInfo.builder()
                        .diagnosisCount(1)
                        .blockerCount(1)
                        .evidenceGapCount(1)
                        .sourceUrls(List.of("https://docs.notion.so/ai"))
                        .sections(List.of(DiagnosisSection.builder()
                                .section("结论")
                                .evidenceInsufficient(true)
                                .repairSuggestions(List.of("补充证据编号并降低结论强度。"))
                                .diagnoses(List.of(DiagnosisItem.builder()
                                        .reviewStage("INITIAL_REVIEW")
                                        .diagnosis(QualityDiagnosis.builder()
                                                .section("结论")
                                                .level("BLOCKER")
                                                .title("关键结论缺少来源引用")
                                                .repairSuggestion("补充证据编号并降低结论强度。")
                                                .build().normalized())
                                        .evidenceReferences(List.of(EvidenceReference.builder()
                                                .evidenceId("E-001")
                                                .title("Notion AI Docs")
                                                .url("https://docs.notion.so/ai")
                                                .competitorName("Notion AI")
                                                .sourceType("DOCS")
                                                .contentSnippet("docs snippet")
                                                .build()))
                                        .build()))
                                .build()))
                        .build())
                .build();
        when(reportService.getReport(99L)).thenReturn(response);

        mockMvc.perform(get("/api/report/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportDiagnosis.diagnosisCount").value(1))
                .andExpect(jsonPath("$.data.reportDiagnosis.sections[0].section").value("结论"))
                .andExpect(jsonPath("$.data.reportDiagnosis.sections[0].diagnoses[0].reviewStage").value("INITIAL_REVIEW"))
                .andExpect(jsonPath("$.data.reportDiagnosis.sections[0].diagnoses[0].evidenceReferences[0].evidenceId").value("E-001"))
                .andExpect(jsonPath("$.data.reportDiagnosis.sections[0].repairSuggestions[0]").value("补充证据编号并降低结论强度。"));
    }
}
