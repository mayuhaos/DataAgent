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
package com.alibaba.cloud.ai.dataagent.config;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adds conversation storage to installations created before the multi-turn API. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSchemaInitializer {

	private final DataSource dataSource;
	private final JdbcTemplate jdbcTemplate;

	@PostConstruct
	public void initialize() throws SQLException {
		createTableIfMissing("conversation_topic", """
			CREATE TABLE conversation_topic (
			 id VARCHAR(36) NOT NULL PRIMARY KEY, session_id VARCHAR(36) NOT NULL, title VARCHAR(255) NOT NULL,
			 summary TEXT, status VARCHAR(20) NOT NULL DEFAULT 'active',
			 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
			""");
		createTableIfMissing("conversation_audit", """
			CREATE TABLE conversation_audit (
			 id VARCHAR(36) NOT NULL PRIMARY KEY, session_id VARCHAR(36) NOT NULL, thread_id VARCHAR(36) NOT NULL,
			 model_context_hash VARCHAR(64) NOT NULL, operation_plan TEXT NOT NULL, validation_result VARCHAR(64) NOT NULL,
			 execution_mode VARCHAR(32) NOT NULL, referenced_artifact_ids TEXT,
			 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
			""");
		ensureArtifactColumn("topic_id", "VARCHAR(36)");
		ensureArtifactColumn("type", "VARCHAR(32) DEFAULT 'QUERY_RESULT'");
		ensureArtifactColumn("input_spec", "TEXT");
		ensureArtifactColumn("result_ref", "VARCHAR(512)");
		ensureArtifactColumn("content_ref", "VARCHAR(512)");
		ensureArtifactColumn("provenance", "TEXT");
		ensureArtifactColumn("expire_time", "TIMESTAMP NULL");
	}

	private void createTableIfMissing(String tableName, String ddl) throws SQLException {
		if (hasTable(tableName)) {
			return;
		}
		log.info("Creating {} for multi-turn conversations", tableName);
		jdbcTemplate.execute(ddl);
	}

	private void ensureArtifactColumn(String columnName, String definition) throws SQLException {
		if (!hasTable("analysis_artifact") || hasColumn("analysis_artifact", columnName)) {
			return;
		}
		log.info("Adding analysis_artifact.{} for multi-turn conversations", columnName);
		jdbcTemplate.execute("ALTER TABLE analysis_artifact ADD COLUMN " + columnName + " " + definition);
	}

	private boolean hasTable(String tableName) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			for (String candidate : new String[] { tableName, tableName.toUpperCase() }) {
				try (ResultSet tables = connection.getMetaData().getTables(null, null, candidate, new String[] { "TABLE" })) {
					if (tables.next()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean hasColumn(String tableName, String columnName) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			for (String candidate : new String[] { tableName, tableName.toUpperCase() }) {
				try (ResultSet columns = connection.getMetaData().getColumns(null, null, candidate, null)) {
					while (columns.next()) {
						if (columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

}
