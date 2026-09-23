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
package com.alibaba.cloud.ai.dataagent.vo;

import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/** Client-safe artifact projection. Server-only SQL, result references and provenance are excluded. */
@Data
@Builder
public class AnalysisArtifactResponse {

	private String id;
	private String sessionId;
	private String topicId;
	private String parentArtifactId;
	private String type;
	private String inputSpec;
	private String userQuestion;
	private String resultSchema;
	private String resultSample;
	private String resultSummary;
	private String presentationSpec;
	private String status;
	private LocalDateTime expireTime;
	private LocalDateTime createTime;

	public static AnalysisArtifactResponse from(AnalysisArtifact artifact) {
		return AnalysisArtifactResponse.builder().id(artifact.getId()).sessionId(artifact.getSessionId())
				.topicId(artifact.getTopicId()).parentArtifactId(artifact.getParentArtifactId()).type(artifact.getType())
				.inputSpec(artifact.getInputSpec()).userQuestion(artifact.getUserQuestion())
				.resultSchema(artifact.getResultSchema()).resultSample(artifact.getResultSample())
				.resultSummary(artifact.getResultSummary()).presentationSpec(artifact.getPresentationSpec())
				.status(artifact.getStatus()).expireTime(artifact.getExpireTime()).createTime(artifact.getCreateTime()).build();
	}

}
