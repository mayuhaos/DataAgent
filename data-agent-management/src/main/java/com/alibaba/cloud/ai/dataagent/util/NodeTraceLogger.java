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

import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_CONVERSATION_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

/**
 * Emits one structured, grep-friendly trace for every node input and output.
 */
@Slf4j(topic = "node-trace")
public final class NodeTraceLogger {

	private static final String TRACE_PREFIX = "[NODE_TRACE]";

	private NodeTraceLogger() {
	}

	public static void input(String nodeName, OverAllState state) {
		log.info("{} event=NODE_INPUT conversationId={} threadId={} node={} attempt={} payload={}", TRACE_PREFIX,
				conversationId(state), threadId(state), nodeName, attempt(state), serialize(state));
	}

	public static void output(String nodeName, OverAllState state, Object payload) {
		log.info("{} event=NODE_OUTPUT conversationId={} threadId={} node={} attempt={} payload={}", TRACE_PREFIX,
				conversationId(state), threadId(state), nodeName, attempt(state), serialize(payload));
	}

	public static void streamOutput(String nodeName, OverAllState state, String payload) {
		log.info("{} event=NODE_OUTPUT_STREAM_COMPLETE conversationId={} threadId={} node={} attempt={} payload={}",
				TRACE_PREFIX, conversationId(state), threadId(state), nodeName, attempt(state), payload);
	}

	public static void error(String nodeName, OverAllState state, Throwable error, String partialOutput) {
		log.error("{} event=NODE_ERROR conversationId={} threadId={} node={} attempt={} partialOutput={}", TRACE_PREFIX,
				conversationId(state), threadId(state), nodeName, attempt(state), partialOutput, error);
	}

	public static void cancelled(String nodeName, OverAllState state, String partialOutput) {
		log.warn("{} event=NODE_CANCELLED conversationId={} threadId={} node={} attempt={} partialOutput={}",
				TRACE_PREFIX, conversationId(state), threadId(state), nodeName, attempt(state), partialOutput);
	}

	private static String conversationId(OverAllState state) {
		String conversationId = StateUtil.getStringValue(state, TRACE_CONVERSATION_ID, "");
		return conversationId.isBlank() ? threadId(state) : conversationId;
	}

	private static String threadId(OverAllState state) {
		return StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
	}

	private static int attempt(OverAllState state) {
		return Math.max(1, Math.max(StateUtil.getObjectValue(state, SQL_GENERATE_COUNT, Integer.class, 0) + 1,
				Math.max(StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0) + 1,
						StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0) + 1)));
	}

	private static String serialize(Object value) {
		try {
			if (value instanceof OverAllState state) {
				return JsonUtil.getObjectMapper().writeValueAsString(state.data());
			}
			return JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			return String.valueOf(value);
		}
	}

}
