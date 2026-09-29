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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Collection;

/** Builds the bounded request sent to the remote quality planning Agent. */
@Component
@RequiredArgsConstructor
@ConditionalOnBean(QualitySkillClient.class)
public class QualitySkillPlanner {

	private final QualitySkillClient client;

	private final ObjectMapper objectMapper;

	public QualityAnalysisSpec plan(String canonicalQuery, Collection<String> availableFields) {
		try {
			String request = """
				Return exactly one AnalysisSpec JSON object.
				Canonical query: %s
				Available SQL result fields: %s
				Only select a registered recipe when the supplied fields and query support it.
				No SQL, Python, source rows, credentials, or unconfirmed thresholds are available.
				""".formatted(canonicalQuery, objectMapper.writeValueAsString(availableFields));
			return QualityAnalysisSpec.parse(client.planAnalysis(request), objectMapper);
		}
		catch (Exception exception) {
			throw new IllegalStateException("quality Skill planning failed", exception);
		}
	}

}
