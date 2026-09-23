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
package com.alibaba.cloud.ai.dataagent.service.chat;

import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.mapper.ChatMessageMapper;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.vo.ChatExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Chat Message Service Class
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

	private final ChatMessageMapper chatMessageMapper;

	@Override
	public List<ChatMessage> findBySessionId(String sessionId) {
		return chatMessageMapper.selectBySessionId(sessionId);
	}

	@Override
	public ChatMessage saveMessage(ChatMessage message) {
		ChatMessage existingTimeline = findTimelineForSameThread(message);
		if (existingTimeline != null) {
			log.info("Skipping duplicate execution timeline for session: {}", message.getSessionId());
			return existingTimeline;
		}
		chatMessageMapper.insert(message);
		log.info("Saved message: {} for session: {}", message.getId(), message.getSessionId());
		return message;
	}

	private ChatMessage findTimelineForSameThread(ChatMessage message) {
		if (!"timeline".equals(message.getMessageType()) || !StringUtils.hasText(message.getSessionId())) {
			return null;
		}
		String executionKey = timelineExecutionKey(message.getContent());
		if (!StringUtils.hasText(executionKey)) {
			return null;
		}
		List<ChatMessage> messages = chatMessageMapper.selectBySessionId(message.getSessionId());
		if (messages == null) {
			return null;
		}
		return messages.stream()
			.filter(candidate -> "timeline".equals(candidate.getMessageType()))
			.filter(candidate -> executionKey.equals(timelineExecutionKey(candidate.getContent())))
			.findFirst()
			.orElse(null);
	}

	private String timelineExecutionKey(String content) {
		if (!StringUtils.hasText(content)) {
			return null;
		}
		try {
			JsonNode blocks = JsonUtil.getObjectMapper().readTree(content);
			JsonNode firstResponse = blocks.path(0).path(0);
			String threadId = firstResponse.path("threadId").asText(null);
			JsonNode workflowStartedAt = firstResponse.path("workflowStartedAt");
			if (!StringUtils.hasText(threadId) || !workflowStartedAt.isNumber()) {
				return null;
			}
			return threadId + ":" + workflowStartedAt.asLong();
		}
		catch (Exception ex) {
			return null;
		}
	}

	@Override
	public ChatExecutionResult getExecutionResult(String sessionId) {
		List<ChatMessage> messages = findBySessionId(sessionId);
		List<String> executedSql = new ArrayList<>();
		String timelineResultMd = null;
		String fallbackResultMd = null;

		for (ChatMessage message : messages) {
			if (isAssistantResult(message)) {
				fallbackResultMd = message.getContent();
			}
			if (!"timeline".equals(message.getMessageType()) || !StringUtils.hasText(message.getContent())) {
				continue;
			}

			try {
				JsonNode blocks = JsonUtil.getObjectMapper().readTree(message.getContent());
				if (!blocks.isArray()) {
					continue;
				}
				StringBuilder report = new StringBuilder();
				for (JsonNode block : blocks) {
					if (!block.isArray()) {
						continue;
					}
					for (JsonNode response : block) {
						String nodeName = response.path("nodeName").asText();
						String textType = response.path("textType").asText();
						String text = response.path("text").asText();
						if (nodeName.endsWith("SqlExecuteNode") && "SQL".equals(textType)
								&& StringUtils.hasText(text)) {
							executedSql.add(text.trim());
						}
						if (nodeName.endsWith("ReportGeneratorNode") && "MARK_DOWN".equals(textType)) {
							report.append(text);
						}
					}
				}
				if (StringUtils.hasText(report)) {
					timelineResultMd = report.toString();
				}
			}
			catch (Exception ex) {
				log.warn("Skipping malformed timeline message: {} for session: {}", message.getId(), sessionId);
			}
		}

		return ChatExecutionResult.builder()
			.sessionId(sessionId)
			.sql(executedSql)
			.resultMd(timelineResultMd != null ? timelineResultMd : fallbackResultMd)
			.build();
	}

	private boolean isAssistantResult(ChatMessage message) {
		if (!"assistant".equals(message.getRole()) || !StringUtils.hasText(message.getContent())) {
			return false;
		}
		return "markdown-report".equals(message.getMessageType()) || "text".equals(message.getMessageType());
	}

}
