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

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import org.junit.jupiter.api.Test;

class PresentationSpecEditorTest {

	@Test
	void updatesYAxisBoundWithoutChangingDataConfiguration() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setYAxisMax(150D);

		String result = new PresentationSpecEditor().apply("{\"type\":\"line\",\"title\":\"尺寸趋势\"}", changes);

		assertEquals(150D, JsonUtil.getObjectMapper().readTree(result).path("yAxis").path("max").asDouble());
		assertEquals("line", JsonUtil.getObjectMapper().readTree(result).path("type").asText());
	}
}
