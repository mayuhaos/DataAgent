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
package com.alibaba.cloud.ai.dataagent.service.conversation;

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Applies display-only changes without changing result data or the original query. */
@Component
public class PresentationSpecEditor {

	public String apply(String sourceSpec, OperationPlan.PresentationChanges changes) {
		try {
			ObjectNode spec = parse(sourceSpec);
			putIfPresent(spec, "type", changes.getChartType());
			putIfPresent(spec, "x", changes.getX());
			putIfPresent(spec, "title", changes.getTitle());
			if (changes.getY() != null && !changes.getY().isEmpty()) {
				spec.set("y", JsonUtil.getObjectMapper().valueToTree(changes.getY()));
			}
			setAxis(spec, "xAxis", changes.getXAxisMin(), changes.getXAxisMax());
			setAxis(spec, "yAxis", changes.getYAxisMin(), changes.getYAxisMax());
			if (changes.getLegendVisible() != null) {
				spec.with("legend").put("show", changes.getLegendVisible());
			}
			return JsonUtil.getObjectMapper().writeValueAsString(spec);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("invalid presentation change", ex);
		}
	}

	private ObjectNode parse(String sourceSpec) throws Exception {
		JsonNode parsed = sourceSpec == null || sourceSpec.isBlank() ? null
				: JsonUtil.getObjectMapper().readTree(sourceSpec);
		return parsed instanceof ObjectNode object ? object.deepCopy() : JsonUtil.getObjectMapper().createObjectNode();
	}

	private void setAxis(ObjectNode spec, String key, Double min, Double max) {
		if (min == null && max == null) {
			return;
		}
		ObjectNode axis = spec.with(key);
		if (min != null) axis.put("min", min);
		if (max != null) axis.put("max", max);
	}

	private void putIfPresent(ObjectNode target, String key, String value) {
		if (value != null && !value.isBlank()) {
			target.put(key, value);
		}
	}
}
