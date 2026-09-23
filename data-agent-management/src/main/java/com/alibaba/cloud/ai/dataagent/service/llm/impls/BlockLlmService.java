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
import lombok.AllArgsConstructor;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil.hideThinkingProcess;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CHAT_MODEL_CONFIG_ID;

@AllArgsConstructor
public class BlockLlmService implements LlmService {

	private final AiModelRegistry registry;

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		return hideThinkingProcess(Mono
			.fromCallable(() -> registry.getChatClient().prompt().system(system).user(user).call().chatResponse())
			.flux());
	}

	@Override
	public Flux<ChatResponse> call(String system, String user, Integer modelConfigId) {
		return hideThinkingProcess(Mono.fromCallable(() -> registry.getChatClient(modelConfigId).prompt().system(system)
				.user(user).call().chatResponse()).flux());
	}

	@Override
	public Flux<ChatResponse> call(String system, String user, Class<?> outputType) {
		StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
			.outputType(outputType)
			.maxRepeatAttempts(2)
			.build();
		return hideThinkingProcess(Mono
			.fromCallable(() -> registry.getChatClient()
				.prompt()
				.system(system)
				.user(user)
				.advisors(advisor)
				.call()
				.chatResponse())
			.subscribeOn(Schedulers.boundedElastic())
			.flux());
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		return hideThinkingProcess(
				Mono.fromCallable(() -> registry.getChatClient().prompt().system(system).call().chatResponse()).flux());
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		return hideThinkingProcess(
				Mono.fromCallable(() -> registry.getChatClient().prompt().user(user).call().chatResponse()).flux());
	}

	@Override
	public Flux<ChatResponse> callUser(String user, Class<?> outputType) {
		StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
			.outputType(outputType)
			.maxRepeatAttempts(2)
			.build();
		return hideThinkingProcess(Mono
			.fromCallable(() -> registry.getChatClient().prompt().user(user).advisors(advisor).call().chatResponse())
			.flux());
	}

	@Override
	public Flux<ChatResponse> callWithState(String system, String user, OverAllState state) {
		return hideThinkingProcess(Mono.fromCallable(() -> chatClient(state).prompt().system(system).user(user)
				.call().chatResponse()).flux());
	}

	@Override
	public Flux<ChatResponse> callSystemWithState(String system, OverAllState state) {
		return hideThinkingProcess(Mono.fromCallable(() -> chatClient(state).prompt().system(system)
				.call().chatResponse()).flux());
	}

	@Override
	public Flux<ChatResponse> callUserWithState(String user, OverAllState state) {
		return hideThinkingProcess(Mono.fromCallable(() -> chatClient(state).prompt().user(user)
				.call().chatResponse()).flux());
	}

	private ChatClient chatClient(OverAllState state) {
		return registry.getChatClient(
				StateUtil.getObjectValue(state, CHAT_MODEL_CONFIG_ID, Integer.class, (Integer) null));
	}

}
