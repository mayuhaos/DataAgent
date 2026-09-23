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
package com.alibaba.cloud.ai.dataagent.mapper;

import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AnalysisArtifactMapper {

	@Insert("""
			INSERT INTO analysis_artifact
			(id, session_id, topic_id, parent_artifact_id, type, input_spec, user_question, sql_query, result_ref, content_ref,
			 result_schema, result_sample, result_summary, presentation_spec, provenance, status, expire_time, create_time)
			VALUES (#{id}, #{sessionId}, #{topicId}, #{parentArtifactId}, #{type}, #{inputSpec}, #{userQuestion}, #{sqlQuery}, #{resultRef}, #{contentRef},
			        #{resultSchema}, #{resultSample}, #{resultSummary}, #{presentationSpec}, #{provenance}, #{status}, #{expireTime}, NOW())
			""")
	int insert(AnalysisArtifact artifact);

	@Select("""
			SELECT * FROM analysis_artifact
			WHERE session_id = #{sessionId} AND status = 'SUCCESS'
			ORDER BY create_time DESC, id DESC
			LIMIT 1
			""")
	AnalysisArtifact selectLatestSuccessfulBySessionId(@Param("sessionId") String sessionId);

	@Select("""
			SELECT * FROM analysis_artifact
			WHERE session_id = #{sessionId} AND topic_id = #{topicId} AND status = 'SUCCESS'
			ORDER BY create_time DESC, id DESC LIMIT 1
			""")
	AnalysisArtifact selectLatestSuccessfulBySessionAndTopic(@Param("sessionId") String sessionId,
			@Param("topicId") String topicId);

	@Select("""
			SELECT * FROM analysis_artifact WHERE session_id = #{sessionId}
			ORDER BY create_time DESC, id DESC
			""")
	List<AnalysisArtifact> selectBySessionId(@Param("sessionId") String sessionId);

	@Select("SELECT * FROM analysis_artifact WHERE id = #{id}")
	AnalysisArtifact selectById(@Param("id") String id);

}
