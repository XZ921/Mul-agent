package cn.bugstack.competitoragent.collection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionDeadlineContextTest {

    @Test
    void noneShouldNeverExpire() {
        CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.none();

        assertFalse(deadlineContext.isExpired());
        assertTrue(deadlineContext.canStartWork(1_000L));
    }

    @Test
    void hardDeadlineShouldExpireImmediatelyWhenEpochIsInThePast() {
        CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.hardDeadline(
                System.currentTimeMillis() - 1L,
                0L
        );

        assertTrue(deadlineContext.isExpired());
        assertFalse(deadlineContext.canStartWork(1L));
    }

    @Test
    void hardDeadlineShouldAllowStartingWorkWhenRemainingBudgetIsEnough() {
        CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.hardDeadline(
                System.currentTimeMillis() + 1_000L,
                0L
        );

        assertTrue(deadlineContext.canStartWork(200L));
    }

    @Test
    void hardDeadlineShouldRejectStartingWorkWhenRemainingBudgetIsTooSmall() {
        CollectionDeadlineContext deadlineContext = CollectionDeadlineContext.hardDeadline(
                System.currentTimeMillis() + 100L,
                0L
        );

        assertFalse(deadlineContext.canStartWork(500L));
    }
}
