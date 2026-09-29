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

import com.alibaba.cloud.ai.dataagent.properties.QualitySkillProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal OpenCode Server API adapter. It deliberately sends only the bounded contract
 * request and never SQL text, database credentials, or unfiltered result rows.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = QualitySkillProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
public class OpenCodeQualitySkillClient implements QualitySkillClient {

	private final RestClient client;

	private final ObjectMapper objectMapper;

	private final QualitySkillProperties properties;

	public OpenCodeQualitySkillClient(QualitySkillProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
			throw new IllegalStateException("quality-skill.base-url is required when quality Skill is enabled");
		}
		this.client = RestClient.builder()
			.baseUrl(properties.getBaseUrl())
			.defaultHeaders(headers -> headers.setBasicAuth(properties.getUsername(), properties.getPassword()))
			.requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
				setConnectTimeout(Duration.ofSeconds(5));
				setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
			}})
			.build();
	}

	@Override
	public String planAnalysis(String request) {
		return prompt("quality-python-planner", request);
	}

	@Override
	public String draftReport(String reportFacts) {
		return prompt("quality-report-writer", "Return one ReportDraft JSON for these ReportFacts:\n" + reportFacts);
	}

	private String prompt(String agent, String text) {
		String sessionId = createSession(agent);
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("messageID", UUID.randomUUID().toString());
			body.put("agent", agent);
			body.put("parts", new Object[] { Map.of("type", "text", "text", text) });
			if (properties.getProviderId() != null && properties.getModelId() != null) {
				body.put("model", Map.of("providerID", properties.getProviderId(), "modelID", properties.getModelId()));
			}
			String response = client.post().uri("/session/{id}/message", sessionId)
				.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
			return textPart(response);
		}
		finally {
			try {
				client.delete().uri("/session/{id}", sessionId).retrieve().toBodilessEntity();
			}
			catch (RuntimeException exception) {
				log.warn("Unable to delete remote OpenCode session {}", sessionId);
			}
		}
	}

	private String createSession(String agent) {
		Map<String, Object> body = Map.of("title", "quality-skill-" + agent + "-" + UUID.randomUUID());
		String response = client.post().uri("/session").contentType(MediaType.APPLICATION_JSON).body(body)
			.retrieve().body(String.class);
		try {
			JsonNode id = objectMapper.readTree(response).path("id");
			if (id.isTextual() && !id.asText().isBlank()) {
				return id.asText();
			}
		}
		catch (Exception exception) {
			throw new IllegalStateException("OpenCode returned an invalid session response", exception);
		}
		throw new IllegalStateException("OpenCode returned no session ID");
	}

	private String textPart(String response) {
		try {
			for (JsonNode part : objectMapper.readTree(response).path("parts")) {
				if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
					return part.path("text").asText().trim();
				}
			}
		}
		catch (Exception exception) {
			throw new IllegalStateException("OpenCode returned an invalid Agent response", exception);
		}
		throw new IllegalStateException("OpenCode returned no text response");
	}

}
