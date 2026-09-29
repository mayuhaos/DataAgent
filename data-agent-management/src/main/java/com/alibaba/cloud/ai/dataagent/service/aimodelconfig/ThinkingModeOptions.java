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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ReasoningEffort;
import org.springframework.util.StringUtils;

import java.util.Map;

/** Provider-specific thinking controls for OpenAI-compatible endpoints. */
public final class ThinkingModeOptions {

	private ThinkingModeOptions() {
	}

	public static boolean isQwen(ModelConfigDTO config) {
		return config != null && "qwen".equalsIgnoreCase(config.getProvider());
	}

	public static Map<String, Object> extraBody(ModelConfigDTO config, boolean enabled) {
		return extraBody(config == null ? null : config.getProvider(), enabled);
	}

	public static Map<String, Object> extraBody(String provider, boolean enabled) {
		// DashScope uses enable_thinking, not DeepSeek's thinking.type.
		if ("qwen".equalsIgnoreCase(provider)) {
			return Map.of("enable_thinking", enabled);
		}
		return Map.of("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
	}

	public static String reasoningEffort(ModelConfigDTO config, boolean enabled) {
		if (!enabled || isQwen(config)) {
			return null;
		}
		return StringUtils.hasText(config.getReasoningEffort())
				? ReasoningEffort.fromCode(config.getReasoningEffort()).getCode()
				: ReasoningEffort.HIGH.getCode();
	}
}
