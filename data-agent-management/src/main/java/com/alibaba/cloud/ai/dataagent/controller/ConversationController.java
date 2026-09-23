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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.conversation.ConversationMessageRequest;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.entity.ConversationTopic;
import com.alibaba.cloud.ai.dataagent.service.chat.AnalysisArtifactService;
import com.alibaba.cloud.ai.dataagent.service.conversation.ConversationService;
import com.alibaba.cloud.ai.dataagent.service.conversation.ConversationTopicService;
import com.alibaba.cloud.ai.dataagent.service.conversation.AnalysisResultStore;
import com.alibaba.cloud.ai.dataagent.vo.ConversationMessageResponse;
import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.dataagent.vo.AnalysisArtifactResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ConversationController {

	private final ConversationService conversationService;
	private final ConversationTopicService conversationTopicService;
	private final AnalysisArtifactService analysisArtifactService;
	private final GraphService graphService;
	private final AnalysisResultStore analysisResultStore;

	@PostMapping(value = "/conversations/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphNodeResponse>> submit(@PathVariable String sessionId,
			@RequestBody @Valid ConversationMessageRequest request, ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache");
		response.getHeaders().add("Connection", "keep-alive");
		ConversationMessageResponse prepared = conversationService.submit(sessionId, request);
		GraphNodeResponse planEvent = GraphNodeResponse.builder().agentId(prepared.getAgentId())
				.threadId(prepared.getThreadId()).nodeName("ConversationPlan").textType(com.alibaba.cloud.ai.dataagent.enums.TextType.JSON)
				.text(toJson(prepared)).build();
		Flux<ServerSentEvent<GraphNodeResponse>> plan = Flux.just(ServerSentEvent.builder(planEvent).event("plan").build());
		if (!"DISPATCH_TO_GRAPH".equals(prepared.getStatus())) {
			if (prepared.getResultArtifactId() != null) {
				AnalysisArtifact artifact = analysisArtifactService.findById(prepared.getResultArtifactId());
				if (artifact != null && artifact.getContentRef() != null) {
					String content = analysisResultStore.get(sessionId, artifact.getContentRef());
					GraphNodeResponse report = GraphNodeResponse.builder().agentId(prepared.getAgentId())
							.threadId(prepared.getThreadId()).nodeName("ReportGeneratorNode")
							.textType(com.alibaba.cloud.ai.dataagent.enums.TextType.MARK_DOWN).text(content).build();
					plan = plan.concatWithValues(ServerSentEvent.builder(report).event("message").build());
				}
			}
			GraphNodeResponse completion = GraphNodeResponse.complete(prepared.getAgentId(), prepared.getThreadId());
			return plan.concatWithValues(ServerSentEvent.builder(completion).event("complete").build());
		}
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		graphService.graphStreamProcess(sink, GraphRequest.builder().agentId(prepared.getAgentId())
				.conversationId(sessionId).topicId(prepared.getTopicId()).threadId(prepared.getThreadId())
				.query(request.getUserMessage()).build());
		return plan.concatWith(sink.asFlux());
	}

	private String toJson(ConversationMessageResponse response) {
		try {
			return com.alibaba.cloud.ai.dataagent.util.JsonUtil.getObjectMapper().writeValueAsString(response);
		}
		catch (Exception ex) {
			return "{\"status\":\"ERROR\"}";
		}
	}

	@GetMapping("/conversations/{sessionId}/topics")
	public List<ConversationTopic> topics(@PathVariable String sessionId) {
		return conversationTopicService.findBySessionId(sessionId);
	}

	@GetMapping("/conversations/{sessionId}/artifacts")
	public List<AnalysisArtifactResponse> artifacts(@PathVariable String sessionId) {
		return analysisArtifactService.findBySessionId(sessionId).stream().map(AnalysisArtifactResponse::from).toList();
	}

	@GetMapping("/artifacts/{artifactId}")
	public ResponseEntity<AnalysisArtifactResponse> artifact(@PathVariable String artifactId) {
		AnalysisArtifact artifact = analysisArtifactService.findById(artifactId);
		return artifact == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(AnalysisArtifactResponse.from(artifact));
	}

	@GetMapping(value = "/artifacts/{artifactId}/result", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> artifactResult(@PathVariable String artifactId) {
		AnalysisArtifact artifact = analysisArtifactService.findById(artifactId);
		if (artifact == null) {
			return ResponseEntity.notFound().build();
		}
		if (artifact.getExpireTime() != null && artifact.getExpireTime().isBefore(java.time.LocalDateTime.now())) {
			return ResponseEntity.status(410).body("{\"error\":\"result expired; requery required\"}");
		}
		if (artifact.getResultRef() == null) {
			if (artifact.getContentRef() == null) {
				return ResponseEntity.status(410).body("{\"error\":\"result unavailable; requery required\"}");
			}
			try {
				return ResponseEntity.ok(analysisResultStore.get(artifact.getSessionId(), artifact.getContentRef()));
			}
			catch (IllegalStateException ex) {
				return ResponseEntity.status(410).body("{\"error\":\"report expired; requery required\"}");
			}
		}
		try {
			return ResponseEntity.ok(analysisResultStore.get(artifact.getSessionId(), artifact.getResultRef()));
		}
		catch (IllegalStateException ex) {
			return ResponseEntity.status(410).body("{\"error\":\"result expired; requery required\"}");
		}
	}

}
