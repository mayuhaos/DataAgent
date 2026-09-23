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

import com.alibaba.cloud.ai.dataagent.entity.ConversationTopic;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationTopicMapper {

	@Insert("""
			INSERT INTO conversation_topic (id, session_id, title, summary, status, create_time, update_time)
			VALUES (#{id}, #{sessionId}, #{title}, #{summary}, #{status}, NOW(), NOW())
			""")
	int insert(ConversationTopic topic);

	@Select("SELECT * FROM conversation_topic WHERE session_id = #{sessionId} ORDER BY update_time DESC, id DESC")
	List<ConversationTopic> selectBySessionId(@Param("sessionId") String sessionId);

	@Select("SELECT * FROM conversation_topic WHERE id = #{id}")
	ConversationTopic selectById(@Param("id") String id);

}
