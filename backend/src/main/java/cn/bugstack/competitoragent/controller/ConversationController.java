package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.conversation.ConversationService;
import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一对话入口控制器。
 */
@Tag(name = "Conversation", description = "统一解释型对话入口")
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping("/message")
    @Operation(summary = "Send conversation message")
    public ApiResponse<ConversationResponse> sendMessage(@Valid @RequestBody ConversationMessageRequest request) {
        return ApiResponse.success(conversationService.handleMessage(request));
    }
}
