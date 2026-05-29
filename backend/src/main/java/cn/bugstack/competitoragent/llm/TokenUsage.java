package cn.bugstack.competitoragent.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 用量统计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    private int inputTokens;
    private int outputTokens;
    private int totalTokens;

    public String toJson() {
        return String.format("{\"input\":%d,\"output\":%d,\"total\":%d}",
                inputTokens, outputTokens, totalTokens);
    }
}
