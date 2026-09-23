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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LocalResultTransformerTest {

	@Mock private AnalysisResultStore store;

	@Test
	void appliesSortAndLimitToFullStoredResult() {
		when(store.get("s1", "ref-1")).thenReturn("[{\"amount\":\"20\"},{\"amount\":\"10\"}]");
		when(store.put(eq("s1"), eq("[{\"amount\":\"20\"}]"), any())).thenReturn("ref-2");
		AnalysisArtifact source = AnalysisArtifact.builder().sessionId("s1").resultRef("ref-1").build();
		OperationPlan.DataChanges changes = new OperationPlan.DataChanges();
		changes.setSort("amount desc");
		changes.setLimit(1);

		assertEquals("ref-2", new LocalResultTransformer(store).transform(source, changes));
	}

}
