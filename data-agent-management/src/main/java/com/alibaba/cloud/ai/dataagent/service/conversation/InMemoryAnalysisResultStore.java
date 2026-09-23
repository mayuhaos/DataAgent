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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Default local store; deployments can replace this bean with Redis or object storage. */
@Service
public class InMemoryAnalysisResultStore implements AnalysisResultStore {

	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

	@Override
	public String put(String sessionId, String result, Duration ttl) {
		String ref = UUID.randomUUID().toString();
		entries.put(ref, new Entry(sessionId, result, Instant.now().plus(ttl)));
		return ref;
	}

	@Override
	public String get(String sessionId, String resultRef) {
		Entry entry = entries.get(resultRef);
		if (entry == null || entry.expireTime().isBefore(Instant.now())) {
			entries.remove(resultRef);
			throw new IllegalStateException("result expired");
		}
		if (!entry.sessionId().equals(sessionId)) {
			throw new SecurityException("result does not belong to session");
		}
		return entry.result();
	}

	private record Entry(String sessionId, String result, Instant expireTime) {
	}

}
