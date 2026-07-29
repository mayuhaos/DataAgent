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

import com.alibaba.cloud.ai.dataagent.entity.AgentSchemaMetadataSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentSchemaMetadataSnapshotMapper {

	@Select("""
			SELECT agent_id AS agentId, datasource_id AS datasourceId,
			       metadata_json AS metadataJson, generated_at AS generatedAt
			FROM agent_schema_metadata_snapshot
			WHERE agent_id = #{agentId}
			""")
	AgentSchemaMetadataSnapshot selectByAgentId(@Param("agentId") Long agentId);

	@Delete("DELETE FROM agent_schema_metadata_snapshot WHERE agent_id = #{agentId}")
	int deleteByAgentId(@Param("agentId") Long agentId);

	@Insert("""
			INSERT INTO agent_schema_metadata_snapshot (agent_id, datasource_id, metadata_json, generated_at)
			VALUES (#{agentId}, #{datasourceId}, #{metadataJson}, #{generatedAt})
			""")
	int insert(AgentSchemaMetadataSnapshot snapshot);

}
