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
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Applies only bounded, lossless local operations to a server-owned result. */
@Service
@RequiredArgsConstructor
public class LocalResultTransformer {

	private final AnalysisResultStore analysisResultStore;

	public String transform(AnalysisArtifact source, OperationPlan.DataChanges changes) {
		if (source.getResultRef() == null) {
			throw new IllegalStateException("result unavailable");
		}
		try {
			JsonNode parsed = JsonUtil.getObjectMapper().readTree(analysisResultStore.get(source.getSessionId(), source.getResultRef()));
			if (!parsed.isArray()) {
				throw new IllegalStateException("result is not an array");
			}
			List<JsonNode> rows = new ArrayList<>();
			parsed.forEach(rows::add);
			sort(rows, changes.getSort());
			int limit = changes.getLimit() == null ? rows.size() : Math.min(rows.size(), Math.max(0, changes.getLimit()));
			ArrayNode transformed = JsonUtil.getObjectMapper().createArrayNode();
			rows.subList(0, limit).forEach(transformed::add);
			return analysisResultStore.put(source.getSessionId(), transformed.toString(), Duration.ofHours(1));
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("result transformation failed", ex);
		}
	}

	private void sort(List<JsonNode> rows, String sort) {
		if (sort == null || sort.isBlank()) {
			return;
		}
		String[] parts = sort.trim().split("\\s+", 2);
		String field = parts[0];
		boolean descending = parts.length == 2 && "desc".equalsIgnoreCase(parts[1]);
		Comparator<JsonNode> comparator = Comparator.comparing(node -> node.path(field).asText(),
				Comparator.nullsLast(String::compareTo));
		rows.sort(descending ? comparator.reversed() : comparator);
	}

}
