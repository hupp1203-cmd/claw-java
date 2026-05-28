package com.claw.cli;

import com.claw.core.AgentConfig;
import com.claw.core.SubAgentDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the dispatch_agents sub-agent feature.
 * Requires DEEPSEEK_API_KEY (or ANTHROPIC_API_KEY) in ~/.claw-java/config.
 */
class DispatchAgentsIntegrationTest {

    @Test
    void dispatchTwoSubAgentsInParallel() throws Exception {
        ClawContext ctx = ClawContext.createDefault();
        AgentConfig cfg = ctx.config().withMaxToolRounds(5);

        var dispatcher = new SubAgentDispatcher(
                cfg,
                ctx.createProviderCallback(cfg),
                ctx.createToolExecutor());

        List<SubAgentDispatcher.SubAgentSpec> specs = List.of(
                new SubAgentDispatcher.SubAgentSpec(
                        "agent-math",
                        "What is 17 * 23? Answer with just the number."),
                new SubAgentDispatcher.SubAgentSpec(
                        "agent-capital",
                        "What is the capital of France? Answer with just the city name.")
        );

        String merged = dispatcher.dispatchAndMerge(specs);

        System.out.println("=== Merged sub-agent results ===");
        System.out.println(merged);
        System.out.println("================================");

        assertNotNull(merged);
        assertFalse(merged.isBlank(), "Merged result should not be blank");
        assertTrue(merged.contains("agent-math"), "Should contain agent-math result header");
        assertTrue(merged.contains("agent-capital"), "Should contain agent-capital result header");
        // Verify both agents completed and returned a non-empty result under their headers
        int mathHeaderIdx = merged.indexOf("[agent-math]");
        int capitalHeaderIdx = merged.indexOf("[agent-capital]");
        assertTrue(mathHeaderIdx >= 0, "Should contain agent-math header");
        assertTrue(capitalHeaderIdx >= 0, "Should contain agent-capital header");

        // Each agent section should have content after its header line
        String mathSection = merged.substring(mathHeaderIdx);
        String capitalSection = merged.substring(capitalHeaderIdx);
        assertTrue(mathSection.length() > 80, "agent-math section should have result content");
        assertTrue(capitalSection.length() > 80, "agent-capital section should have result content");
        assertTrue(merged.toLowerCase().contains("paris"), "Capital agent should return Paris");
    }
}
