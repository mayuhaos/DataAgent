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
package com.alibaba.cloud.ai.dataagent.service.graph.Context;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Records the actual entry time for a workflow node before it produces streaming
 * output. Stream events can otherwise arrive substantially later than execution begins.
 */
@Component
public class NodeTimingRegistry {

	private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> nodeStarts = new ConcurrentHashMap<>();

	public void recordNodeStart(String threadId, String nodeName, long startedAt) {
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(nodeName)) {
			return;
		}
		nodeStarts.computeIfAbsent(threadId, ignored -> new ConcurrentHashMap<>()).put(nodeName, startedAt);
	}

	/**
	 * Returns and removes the start for this node so a later invocation of the same
	 * node receives its own entry time.
	 */
	public Long takeNodeStart(String threadId, String nodeName) {
		ConcurrentHashMap<String, Long> starts = nodeStarts.get(threadId);
		if (starts == null) {
			return null;
		}
		Long startedAt = starts.remove(nodeName);
		if (starts.isEmpty()) {
			nodeStarts.remove(threadId, starts);
		}
		return startedAt;
	}

	public void clear(String threadId) {
		if (StringUtils.hasText(threadId)) {
			nodeStarts.remove(threadId);
		}
	}

}
