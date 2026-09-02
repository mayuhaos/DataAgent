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
import com.alibaba.cloud.ai.dataagent.vo.ChatExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceImplTest {

	private ChatMessageServiceImpl service;

	@Mock
	private ChatMessageMapper chatMessageMapper;

	@BeforeEach
	void setUp() {
		service = new ChatMessageServiceImpl(chatMessageMapper);
	}

	@Test
	void findBySessionId_returnsMessages() {
		String sessionId = "session-1";
		List<ChatMessage> expected = List.of(
				ChatMessage.builder().id(1L).sessionId(sessionId).role("user").content("hello").build(),
				ChatMessage.builder().id(2L).sessionId(sessionId).role("assistant").content("hi").build());
		when(chatMessageMapper.selectBySessionId(sessionId)).thenReturn(expected);

		List<ChatMessage> result = service.findBySessionId(sessionId);

		assertEquals(2, result.size());
		assertEquals("user", result.get(0).getRole());
		assertEquals("assistant", result.get(1).getRole());
		verify(chatMessageMapper).selectBySessionId(sessionId);
	}

	@Test
	void findBySessionId_returnsEmptyList() {
		when(chatMessageMapper.selectBySessionId("empty")).thenReturn(List.of());

		List<ChatMessage> result = service.findBySessionId("empty");

		assertTrue(result.isEmpty());
	}

	@Test
	void saveMessage_insertsAndReturnsMessage() {
		ChatMessage message = ChatMessage.builder()
			.id(1L)
			.sessionId("session-1")
			.role("user")
			.content("test message")
			.build();

		ChatMessage result = service.saveMessage(message);

		assertSame(message, result);
		verify(chatMessageMapper).insert(message);
	}

	@Test
	void getExecutionResult_extractsOnlyExecutedSqlAndReportMarkdown() {
		String timeline = """
				[[
				  {"nodeName":"SqlGenerateNode","textType":"SQL","text":"SELECT generated_only"},
				  {"nodeName":"SqlExecuteNode","textType":"TEXT","text":"execute"},
				  {"nodeName":"SqlExecuteNode","textType":"SQL","text":" SELECT actual_1 "},
				  {"nodeName":"SqlExecuteNode","textType":"RESULT_SET","text":"{}"}
				],[
				  {"nodeName":"SqlExecuteNode","textType":"SQL","text":"SELECT actual_2"}
				],[
				  {"nodeName":"ReportGeneratorNode","textType":"MARK_DOWN","text":"# Report\\n"},
				  {"nodeName":"ReportGeneratorNode","textType":"MARK_DOWN","text":"| value |\\n| --- |"}
				]]
				""";
		when(chatMessageMapper.selectBySessionId("session-1"))
			.thenReturn(List.of(ChatMessage.builder()
				.id(1L)
				.sessionId("session-1")
				.role("assistant")
				.messageType("timeline")
				.content(timeline)
				.build()));

		ChatExecutionResult result = service.getExecutionResult("session-1");

		assertEquals("session-1", result.getSessionId());
		assertEquals(List.of("SELECT actual_1", "SELECT actual_2"), result.getSql());
		assertEquals("# Report\n| value |\n| --- |", result.getResultMd());
	}

	@Test
	void getExecutionResult_malformedTimelineFallsBackToLatestAssistantText() {
		when(chatMessageMapper.selectBySessionId("session-1"))
			.thenReturn(List.of(
					ChatMessage.builder()
						.id(1L)
						.sessionId("session-1")
						.role("assistant")
						.messageType("timeline")
						.content("not-json")
						.build(),
					ChatMessage.builder()
						.id(2L)
						.sessionId("session-1")
						.role("assistant")
						.messageType("text")
						.content("## Final answer")
						.build()));

		ChatExecutionResult result = service.getExecutionResult("session-1");

		assertTrue(result.getSql().isEmpty());
		assertEquals("## Final answer", result.getResultMd());
	}

	@Test
	void getExecutionResult_emptySessionReturnsEmptySqlAndNullMarkdown() {
		when(chatMessageMapper.selectBySessionId("session-1")).thenReturn(List.of());

		ChatExecutionResult result = service.getExecutionResult("session-1");

		assertTrue(result.getSql().isEmpty());
		assertNull(result.getResultMd());
	}

}
