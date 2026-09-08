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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.enums.TextType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * @author zhangshenghang
 */
public class ChatResponseUtil {

	public static ChatResponse createResponse(String statusMessage) {
		return createPureResponse(statusMessage + "\n");
	}

	public static ChatResponse createPureResponse(String message) {
		AssistantMessage assistantMessage = new AssistantMessage(message);
		Generation generation = new Generation(assistantMessage);
		return new ChatResponse(List.of(generation));
	}

	// 这样无法达到效果，先弃用。如果不得不需要这个逻辑，再重新定义
	@Deprecated
	public static ChatResponse createTrimResponse(String message, TextType textType) {
		return createPureResponse(message.replace(textType.getStartSign(), "").replace(textType.getEndSign(), ""));
	}

	public static String getText(ChatResponse chatResponse) {
		Generation result = chatResponse.getResult();
		if (result == null) {
			return "";
		}
		AssistantMessage output = result.getOutput();
		if (output == null) {
			return "";
		}
		return output.getText() == null ? "" : output.getText();
	}

	/**
	 * Removes reasoning emitted inline as {@code <think>...</think>} while preserving
	 * response and generation metadata. The filter keeps state across chunks because
	 * model providers may split the tags between SSE events.
	 */
	public static Flux<ChatResponse> hideThinkingProcess(Flux<ChatResponse> responseFlux) {
		return Flux.defer(() -> {
			ThinkingProcessFilter filter = new ThinkingProcessFilter();
			Flux<ChatResponse> filtered = responseFlux.map(response -> replaceText(response,
					filter.filter(getText(response))));
			return filtered.concatWith(Mono.defer(() -> {
				String remaining = filter.finish();
				return remaining.isEmpty() ? Mono.empty() : Mono.just(createPureResponse(remaining));
			}));
		});
	}

	private static ChatResponse replaceText(ChatResponse response, String text) {
		Generation result = response.getResult();
		if (result == null || result.getOutput() == null) {
			return response;
		}

		AssistantMessage output = result.getOutput();
		AssistantMessage filteredOutput = AssistantMessage.builder()
			.content(text)
			.properties(output.getMetadata())
			.toolCalls(output.getToolCalls())
			.media(output.getMedia())
			.build();
		Generation filteredGeneration = new Generation(filteredOutput, result.getMetadata());
		return new ChatResponse(List.of(filteredGeneration), response.getMetadata());
	}

	private static final class ThinkingProcessFilter {

		private static final String START_TAG = "<think>";

		private static final String END_TAG = "</think>";

		private final StringBuilder pending = new StringBuilder();

		private boolean thinking;

		String filter(String chunk) {
			if (chunk != null) {
				pending.append(chunk);
			}

			StringBuilder visible = new StringBuilder();
			while (!pending.isEmpty()) {
				String tag = thinking ? END_TAG : START_TAG;
				String buffered = pending.toString();
				int tagIndex = buffered.toLowerCase(Locale.ROOT).indexOf(tag);
				if (tagIndex >= 0) {
					if (!thinking) {
						visible.append(buffered, 0, tagIndex);
					}
					pending.delete(0, tagIndex + tag.length());
					thinking = !thinking;
					continue;
				}

				int retained = partialTagLength(buffered, tag);
				int consumable = buffered.length() - retained;
				if (!thinking) {
					visible.append(buffered, 0, consumable);
				}
				pending.delete(0, consumable);
				break;
			}
			return visible.toString();
		}

		String finish() {
			if (thinking) {
				pending.setLength(0);
				return "";
			}
			String remaining = pending.toString();
			pending.setLength(0);
			return remaining;
		}

		private int partialTagLength(String value, String tag) {
			String lowerValue = value.toLowerCase(Locale.ROOT);
			for (int length = Math.min(value.length(), tag.length() - 1); length > 0; length--) {
				if (tag.startsWith(lowerValue.substring(value.length() - length))) {
					return length;
				}
			}
			return 0;
		}

	}

}
