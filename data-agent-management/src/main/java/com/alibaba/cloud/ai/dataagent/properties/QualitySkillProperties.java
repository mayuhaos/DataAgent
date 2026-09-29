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
package com.alibaba.cloud.ai.dataagent.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PROJECT_PROPERTIES_PREFIX;

/** Configuration for the remote, no-tool OpenCode quality Skills. */
@Getter
@Setter
@ConfigurationProperties(prefix = QualitySkillProperties.CONFIG_PREFIX)
public class QualitySkillProperties {

	public static final String CONFIG_PREFIX = PROJECT_PROPERTIES_PREFIX + ".quality-skill";

	/** Disabled until the remote OpenCode service and its provider are verified. */
	private boolean enabled = false;

	/** Base URL of {@code opencode serve}, normally reachable only on the service network. */
	private String baseUrl;

	private String username = "opencode";

	private String password;

	/** OpenCode model object uses provider ID and model ID separately. */
	private String providerId;

	private String modelId;

	private long timeoutSeconds = 45;

}
