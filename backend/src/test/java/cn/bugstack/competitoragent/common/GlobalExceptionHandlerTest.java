package cn.bugstack.competitoragent.common;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    @Test
    void shouldIgnoreSseDisconnectExceptionWhenResponseAlreadyCommitted() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");
        response.setCommitted(true);

        ApiResponse<Map<String, Object>> apiResponse = handler.handleUnknownException(
                new AsyncRequestNotUsableException(
                        "response not usable after async request completed",
                        new ClientAbortException("client aborted")
                ),
                response
        );

        assertNull(apiResponse);
    }

    @Test
    void shouldStillReturnApiResponseForRegularUnknownException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResponse<Map<String, Object>> apiResponse = handler.handleUnknownException(
                new IllegalStateException("boom"),
                response
        );

        assertEquals(ResultCode.INTERNAL_ERROR.getCode(), apiResponse.getCode());
        assertEquals("IllegalStateException", apiResponse.getData().get("errorType"));
    }
}
