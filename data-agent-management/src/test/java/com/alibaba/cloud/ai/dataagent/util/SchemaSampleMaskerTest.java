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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSampleMaskerTest {

	@Test
	void masksSensitiveValuesAndLimitsSamples() {
		assertEquals(List.of("138***5678"), SchemaSampleMasker.mask("mobile_phone", List.of("13812345678")));
		assertEquals(List.of("a***@example.com"), SchemaSampleMasker.mask("email", List.of("alice@example.com")));
		assertEquals(List.of("张***"), SchemaSampleMasker.mask("real_name", List.of("张三")));
		assertEquals(List.of("A", "B", "C"), SchemaSampleMasker.mask("status", List.of("A", "B", "C", "D")));
	}

	@Test
	void omitsSecretColumns() {
		assertTrue(SchemaSampleMasker.mask("access_token", List.of("secret-value")).isEmpty());
		assertTrue(SchemaSampleMasker.mask("密码", List.of("123456")).isEmpty());
	}

}
