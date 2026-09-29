/*
 * Copyright 2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.service.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/** Validates the minimal contract required before a quality recipe may be executed. */
public record QualityAnalysisSpec(String rawJson, String mode, String recipeId) {

	private static final Set<String> RECIPE_IDS = Set.of("tabular_aggregate_v1", "dimension_variation_v1");

	public static QualityAnalysisSpec parse(String rawJson, ObjectMapper objectMapper) {
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			if (!"1.0".equals(root.path("contract_version").asText())) {
				throw new IllegalArgumentException("unsupported AnalysisSpec contract version");
			}
			String mode = root.path("mode").asText();
			if ("legacy".equals(mode)) {
				return new QualityAnalysisSpec(rawJson, mode, null);
			}
			String recipeId = root.path("recipe_id").asText();
			if (!"recipe".equals(mode) || !RECIPE_IDS.contains(recipeId) || !root.path("parameters").isObject()) {
				throw new IllegalArgumentException("invalid quality AnalysisSpec");
			}
			return new QualityAnalysisSpec(rawJson, mode, recipeId);
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("OpenCode returned an invalid AnalysisSpec", exception);
		}
	}

}
