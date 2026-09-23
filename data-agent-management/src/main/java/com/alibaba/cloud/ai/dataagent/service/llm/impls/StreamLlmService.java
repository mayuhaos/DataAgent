/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.llm.impls;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.enums.ReasoningEffort;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.REASONING_EFFORT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CHAT_MODEL_CONFIG_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.THINKING_ENABLED;
import static com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil.hideThinkingProcess;

public class StreamLlmService implements LlmService {

	private final AiModelRegistry registry;

	private final Duration responseTimeout;

	public StreamLlmService(AiModelRegistry registry) {
		this(registry, Duration.ofSeconds(120));
	}

	public StreamLlmService(AiModelRegistry registry, Duration responseTimeout) {
		this.registry = registry;
		this.responseTimeout = responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()
				? Duration.ofSeconds(120) : responseTimeout;
	}

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		return hideThinkingProcess(withTimeout(retry -> configuredChatClient(null, retry).prompt().system(system).user(user).stream()
			.chatResponse()));
	}

	@Override
	public Flux<ChatResponse> call(String system, String user, Integer modelConfigId) {
		return hideThinkingProcess(withTimeout(retry -> configuredChatClient(modelConfigId, retry).prompt().system(system).user(user)
			.stream().chatResponse()));
	}

	@Override
	public Flux<ChatResponse> call(String system, String user, Class<?> outputType) {
		return hideThinkingProcess(withTimeout(retry -> {
			StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
				.outputType(outputType)
				.maxRepeatAttempts(2)
				.build();
			return Mono.fromCallable(() -> configuredChatClient(null, retry)
				.prompt()
				.system(system)
				.user(user)
				.advisors(advisor)
				.call()
				.chatResponse())
				.subscribeOn(Schedulers.boundedElastic())
				.flux();
		}));
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		return hideThinkingProcess(withTimeout(retry -> configuredChatClient(null, retry).prompt().system(system).stream().chatResponse()));
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		return hideThinkingProcess(withTimeout(retry -> configuredChatClient(null, retry).prompt().user(user).stream().chatResponse()));
	}

	@Override
	public Flux<ChatResponse> callUser(String user, Class<?> outputType) {
		return hideThinkingProcess(withTimeout(retry -> {
			StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
				.outputType(outputType)
				.maxRepeatAttempts(2)
				.build();
			return Mono
				.fromCallable(() -> configuredChatClient(null, retry).prompt().user(user).advisors(advisor).call().chatResponse())
				.subscribeOn(Schedulers.boundedElastic())
				.flux();
		}));
	}

	@Override
	public Flux<ChatResponse> callWithState(String system, String user, OverAllState state) {
		return callWithState(system, user, state, null);
	}

	@Override
	public Flux<ChatResponse> callWithState(String system, String user, OverAllState state, boolean thinkingEnabled) {
		return callWithState(system, user, state, Boolean.valueOf(thinkingEnabled));
	}

	private Flux<ChatResponse> callWithState(String system, String user, OverAllState state, Boolean thinkingOverride) {
		return hideThinkingProcess(withTimeout(retry -> applyThinkingOptions(chatClient(state, retry).prompt(), state, thinkingOverride)
			.system(system).user(user).stream().chatResponse()));
	}

	@Override
	public Flux<ChatResponse> callSystemWithState(String system, OverAllState state) {
		return hideThinkingProcess(withTimeout(retry ->
					applyThinkingOptions(chatClient(state, retry).prompt(), state).system(system).stream().chatResponse()));
	}

	@Override
	public Flux<ChatResponse> callUserWithState(String user, OverAllState state) {
		return callUserWithState(user, state, null);
	}

	@Override
	public Flux<ChatResponse> callUserWithState(String user, OverAllState state, boolean thinkingEnabled) {
		return callUserWithState(user, state, Boolean.valueOf(thinkingEnabled));
	}

	private Flux<ChatResponse> callUserWithState(String user, OverAllState state, Boolean thinkingOverride) {
		return hideThinkingProcess(withTimeout(retry -> applyThinkingOptions(chatClient(state, retry).prompt(), state, thinkingOverride)
			.user(user).stream().chatResponse()));
	}

	private Flux<ChatResponse> withTimeout(Function<Boolean, Flux<ChatResponse>> call) {
		AtomicInteger attempt = new AtomicInteger();
		AtomicBoolean modelTokenReceived = new AtomicBoolean();
		return Flux.defer(() -> call.apply(attempt.getAndIncrement() > 0)
			.doOnNext(response -> modelTokenReceived.set(true)))
			.timeout(responseTimeout)
			.retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
				.maxBackoff(Duration.ofSeconds(4))
				.jitter(0.3)
				.filter(error -> !modelTokenReceived.get() && isRetryable(error)));
	}

	private boolean isRetryable(Throwable error) {
		if (error instanceof java.util.concurrent.TimeoutException) {
			return true;
		}
		String message = error.getMessage();
		if (message == null) {
			return false;
		}
		String normalized = message.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("429") || normalized.contains("too many requests")
				|| normalized.contains("connection reset") || normalized.contains("connection refused")
				|| normalized.contains("connection timed out") || normalized.contains("503")
				|| normalized.contains("502") || normalized.contains("504");
	}

	private ChatClient.ChatClientRequestSpec applyThinkingOptions(ChatClient.ChatClientRequestSpec spec,
			OverAllState state) {
		return applyThinkingOptions(spec, state, null);
	}

	private ChatClient chatClient(OverAllState state, boolean retry) {
		Integer modelConfigId = StateUtil.getObjectValue(state, CHAT_MODEL_CONFIG_ID, Integer.class, (Integer) null);
		return configuredChatClient(modelConfigId, retry);
	}

	private ChatClient configuredChatClient(Integer modelConfigId, boolean retry) {
		if (retry) {
			return registry.createRequestChatClient(modelConfigId);
		}
		return modelConfigId == null ? registry.getChatClient() : registry.getChatClient(modelConfigId);
	}

	private ChatClient.ChatClientRequestSpec applyThinkingOptions(ChatClient.ChatClientRequestSpec spec,
			OverAllState state, Boolean thinkingOverride) {
		Boolean enabled = thinkingOverride != null ? thinkingOverride
			: StateUtil.getObjectValue(state, THINKING_ENABLED, Boolean.class, (Boolean) null);
		if (enabled == null) {
			return spec;
		}

		String type = Boolean.TRUE.equals(enabled) ? "enabled" : "disabled";
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
			.extraBody(Map.of("thinking", Map.of("type", type)));
		if (Boolean.TRUE.equals(enabled)) {
			String effort = ReasoningEffort
				.fromCode(StateUtil.getStringValue(state, REASONING_EFFORT, ReasoningEffort.HIGH.getCode()))
				.getCode();
			options.reasoningEffort(effort);
		}
		return spec.options(options.build());
	}

}
