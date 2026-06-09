package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.event.TaskEventReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskEventStreamControllerTest {

    private TaskEventReplayService taskEventReplayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskEventReplayService = mock(TaskEventReplayService.class);
        when(taskEventReplayService.subscribe(42L, null)).thenReturn(new SseEmitter());
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskEventStreamController(taskEventReplayService)).build();
    }

    @Test
    void shouldExposeStructuredSseEndpointWithAsyncEmitter() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/task/42/events"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertNotNull(WebAsyncUtils.getAsyncManager(result.getRequest()).getConcurrentResultContext());
    }

    @Test
    void shouldForwardResumeCursorFromHeaderOrQueryParameter() throws Exception {
        when(taskEventReplayService.subscribe(77L, "77-8")).thenReturn(new SseEmitter());
        when(taskEventReplayService.subscribe(78L, "78-5")).thenReturn(new SseEmitter());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/task/77/events")
                        .header("Last-Event-ID", "77-8"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/task/78/events")
                        .queryParam("cursor", "78-5"))
                .andExpect(status().isOk());

        verify(taskEventReplayService).subscribe(77L, "77-8");
        verify(taskEventReplayService).subscribe(78L, "78-5");
    }
}
