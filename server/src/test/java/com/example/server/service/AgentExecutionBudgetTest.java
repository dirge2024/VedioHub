package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionBudgetTest {

    @Test
    void rejectsWorkAfterDeadline() throws Exception {
        try (AgentExecutionBudget.Scope ignored = AgentExecutionBudget.open(1)) {
            Thread.sleep(5);
            assertThrows(
                    AgentExecutionBudget.DeadlineExceededException.class,
                    AgentExecutionBudget::remainingMillis);
        }
        assertDoesNotThrow(AgentExecutionBudget::remainingMillis);
    }
}
