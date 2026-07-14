package cn.bugstack.competitoragent.orchestration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Orchestrator Prompt/Response 指纹的唯一算法 owner。
 * 本类不持有 Logger，避免任何调用路径意外输出待哈希原文或中间字节。
 */
final class OrchestrationDecisionHashing {

    private static final String SHA_256 = "SHA-256";
    private static final String PREFIX = "sha256:";

    private OrchestrationDecisionHashing() {
    }

    static String hashPrompt(OrchestrationDecisionPrompt prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        MessageDigest digest = newDigest();
        // 长度前缀固定字段边界，避免 ("ab", "c") 与 ("a", "bc") 产生相同输入串。
        updateLengthPrefixed(digest, prompt.systemPrompt());
        updateLengthPrefixed(digest, prompt.userPrompt());
        updateLengthPrefixed(digest, prompt.responseSchema());
        return PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    static String hashResponse(String rawResponse) {
        if (rawResponse == null) {
            return null;
        }
        MessageDigest digest = newDigest();
        digest.update(rawResponse.getBytes(StandardCharsets.UTF_8));
        return PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
