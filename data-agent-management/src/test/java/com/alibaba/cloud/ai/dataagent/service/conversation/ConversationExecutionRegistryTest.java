package com.alibaba.cloud.ai.dataagent.service.conversation;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class ConversationExecutionRegistryTest {

	@Test
	void blocksConcurrentSubmissionAndReleasesOnlyTheOwningRegistration() {
		var registry = new ConversationExecutionRegistry();
		var old = registry.begin("session");
		assertThrows(IllegalStateException.class, () -> registry.begin("session"));
		old.close();
		try (var current = registry.begin("session")) {
			old.close();
			assertTrue(registry.cancel("session"));
			assertTrue(current.isCancelled());
		}
		assertFalse(registry.cancel("session"));
	}

	@Test
	void cancellationIsReplayableToALateSubscriberAndIdempotentWithinTheTask() {
		var registry = new ConversationExecutionRegistry();
		try (var task = registry.begin("session")) {
			assertTrue(registry.cancel("session"));
			assertTrue(registry.cancel("session"));
			StepVerifier.create(task.cancellation()).expectNext(true).verifyComplete();
		}
	}
}
