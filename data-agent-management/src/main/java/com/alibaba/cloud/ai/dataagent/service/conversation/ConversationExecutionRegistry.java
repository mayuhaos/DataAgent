package com.alibaba.cloud.ai.dataagent.service.conversation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Tracks submissions before a graph thread exists, including blocking planning. */
@Component
public class ConversationExecutionRegistry {

	private final ConcurrentHashMap<String, Registration> active = new ConcurrentHashMap<>();

	public Registration begin(String sessionId) {
		Registration registration = new Registration(sessionId);
		if (active.putIfAbsent(sessionId, registration) != null) {
			throw new IllegalStateException("当前会话已有任务正在执行，请等待完成或停止后再提问。");
		}
		return registration;
	}

	public boolean cancel(String sessionId) {
		Registration registration = active.get(sessionId);
		return registration != null && registration.cancel();
	}

	public final class Registration implements AutoCloseable {

		private final String sessionId;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final Sinks.One<Boolean> cancellation = Sinks.one();

		private Registration(String sessionId) { this.sessionId = sessionId; }

		public boolean isCancelled() { return cancelled.get(); }

		public Mono<Boolean> cancellation() { return cancellation.asMono(); }

		public boolean cancel() {
			if (cancelled.compareAndSet(false, true)) cancellation.tryEmitValue(true);
			return true;
		}

		@Override
		public void close() { active.remove(sessionId, this); }
	}
}
