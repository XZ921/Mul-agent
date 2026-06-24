package cn.bugstack.competitoragent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 Task 1 先锁定白名单模型的最小完整性约束。
 * 这里不判断边界规则本身，只保证每条具名豁免都带有足够的回收信息，
 * 避免后续阶段继续依赖“口头约定”维护历史耦合。
 */
class ArchitectureWhitelistTest {

    private static final Path ARCHITECTURE_WHITELIST_LEDGER = Path.of(
            "docs",
            "superpowers",
            "task-definition-and-orchestration-contract",
            "task",
            "2026-06-10-architecture-whitelist-ledger.md");

    @Test
    void exemptions_should_have_reason_and_remove_phase() {
        for (ArchitectureWhitelist.Exemption exemption : ArchitectureWhitelist.EXEMPTIONS) {
            assertFalse(exemption.ruleName().isBlank());
            assertFalse(exemption.className().isBlank());
            assertFalse(exemption.reason().isBlank());
            assertFalse(exemption.removeByPhase().isBlank());
            assertFalse(exemption.owner().isBlank());
        }
    }

    @Test
    void ledger_should_cover_all_whitelist_entries() throws IOException {
        Path ledgerPath = resolveLedgerPath();
        assertTrue(Files.exists(ledgerPath), () -> "architecture whitelist ledger missing: " + ledgerPath);

        List<String> ledgerLines = Files.readAllLines(ledgerPath);
        List<String> expectedRows = ArchitectureWhitelist.EXEMPTIONS.stream()
                .map(exemption -> "| "
                        + exemption.ruleName() + " | "
                        + exemption.className() + " | "
                        + exemption.reason() + " | "
                        + exemption.removeByPhase() + " | "
                        + exemption.owner() + " |")
                .toList();

        for (String expectedRow : expectedRows) {
            assertTrue(ledgerLines.contains(expectedRow));
        }
        assertEquals(expectedRows.size(), ledgerLines.stream().filter(line -> line.startsWith("| ")).count() - 2);
    }

    private static Path resolveLedgerPath() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        // Maven -pl backend 时工作目录是 backend，IDE 运行时可能是仓库根目录；
        // 这里只解析真实台账位置，不复制台账文件，避免形成第二份白名单事实源。
        List<Path> candidates = List.of(
                workingDirectory.resolve(ARCHITECTURE_WHITELIST_LEDGER).normalize(),
                workingDirectory.resolve("..").normalize().resolve(ARCHITECTURE_WHITELIST_LEDGER).normalize()
        );
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(candidates.get(0));
    }
}
