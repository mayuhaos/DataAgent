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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig.responses;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.responses.ResponsesApi.StreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResponsesApi SSE 事件解析单元测试。 覆盖 delta / completed / incomplete / failed / error
 * 各事件分支，以及畸形事件的容错行为。
 */
class ResponsesApiTest {

	private ResponsesApi responsesApi;

	@BeforeEach
	void setUp() {
		// 仅测试解析逻辑，不发起真实 HTTP 请求，baseUrl 等参数使用占位值
		responsesApi = new ResponsesApi("http://localhost", "test-key", null, RestClient.builder(),
				WebClient.builder());
	}

	private StreamEvent parse(String data) {
		return responsesApi.parseStreamEvent(ServerSentEvent.builder(data).build());
	}

	@Test
	void synchronousCallReadsAllResponseChunks() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
		server.createContext("/v1/responses", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			try (var output = exchange.getResponseBody()) {
				output.write('{');
				output.flush();
				output.write("\"id\":\"chunked\",\"status\":\"completed\",\"output\":[]}"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		});
		server.start();
		try {
			var api = new ResponsesApi("http://localhost:" + server.getAddress().getPort(), "test-key", null,
				RestClient.builder(), WebClient.builder());
			var result = api.call(new ResponsesApi.ResponsesRequest("test", null, java.util.List.of(),
				null, null, null, null, null, false));
			assertEquals("chunked", result.id());
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void synchronousCallReadsBrotliResponseWithoutSingleByteProbe() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
		server.createContext("/v1/responses", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Content-Encoding", "br");
			exchange.sendResponseHeaders(200, 0);
			// Brotli-compressed {"id":"brotli-response","status":"completed","output":[]}.
			try (var output = exchange.getResponseBody()) {
				output.write(java.util.Base64.getDecoder().decode(
					"CxyAeyJpZCI6ImJyb3RsaS1yZXNwb25zZSIsInN0YXR1cyI6ImNvbXBsZXRlZCIsIm91dHB1dCI6W119Aw=="));
			}
		});
		server.start();
		try {
			var api = new ResponsesApi("http://localhost:" + server.getAddress().getPort(), "test-key", null,
				RestClient.builder(), WebClient.builder());
			var result = api.call(new ResponsesApi.ResponsesRequest("test", null, java.util.List.of(),
				null, null, null, null, null, false));
			assertEquals("brotli-response", result.id());
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void synchronousCallReadsGzipResponseAfterOpeningBraceFlush() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
		server.createContext("/v1/responses", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, 0);
			try (var output = new java.util.zip.GZIPOutputStream(exchange.getResponseBody(), true)) {
				output.write('{');
				output.flush();
				java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
				output.write("\"id\":\"gzip-chunked\",\"status\":\"completed\",\"output\":[]}"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		});
		server.start();
		try {
			var api = new ResponsesApi("http://localhost:" + server.getAddress().getPort(), "test-key", null,
				RestClient.builder(), WebClient.builder());
			var result = api.call(new ResponsesApi.ResponsesRequest("test", null, java.util.List.of(),
				null, null, null, null, null, false));
			assertEquals("gzip-chunked", result.id());
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void synchronousCallPreservesNonRetryableHttpErrors() throws Exception {
		assertHttpError(401, org.springframework.ai.retry.NonTransientAiException.class);
	}

	@Test
	void synchronousCallPreservesRetryableHttpErrors() throws Exception {
		assertHttpError(503, org.springframework.ai.retry.TransientAiException.class);
	}

	private void assertHttpError(int status, Class<? extends Throwable> expected) throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
		server.createContext("/v1/responses", exchange -> {
			exchange.getRequestBody().readAllBytes();
			byte[] body = "{\"error\":{\"message\":\"test error\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		try {
			var api = new ResponsesApi("http://localhost:" + server.getAddress().getPort(), "test-key", null,
				RestClient.builder(), WebClient.builder());
			assertThrows(expected, () -> api.call(new ResponsesApi.ResponsesRequest("test", null, java.util.List.of(),
				null, null, null, null, null, false)));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void parseDeltaEvent_returnsDeltaText() {
		StreamEvent event = parse(
				"{\"type\":\"response.output_text.delta\",\"output_index\":3,\"delta\":\"Hello\"}");
		assertNotNull(event);
		assertEquals(StreamEvent.Type.DELTA, event.type());
		assertEquals("Hello", event.delta());
		assertEquals(3, event.outputIndex());
	}

	@Test
	void parseMessageStart_preservesOutputIndexAndPhase() {
		StreamEvent event = parse("""
				{"type":"response.output_item.added","output_index":1,
				 "item":{"type":"message","role":"assistant","phase":"commentary","content":[]}}
				""");

		assertNotNull(event);
		assertEquals(StreamEvent.Type.MESSAGE_START, event.type());
		assertEquals(1, event.outputIndex());
		assertEquals("commentary", event.phase());
	}

	@Test
	void parseCompletedEvent_returnsResponseWithUsage() {
		String data = """
				{"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed",
				"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}],
				"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}
				""";
		StreamEvent event = parse(data);
		assertNotNull(event);
		assertEquals(StreamEvent.Type.COMPLETED, event.type());
		assertEquals("resp_1", event.response().id());
		assertEquals(10, event.response().usage().inputTokens());
		assertEquals(5, event.response().usage().outputTokens());
	}

	@Test
	void parseIncompleteEvent_returnsIncompleteType() {
		String data = """
				{"type":"response.incomplete","response":{"id":"resp_2","status":"incomplete",
				"incomplete_details":{"reason":"max_output_tokens"}}}
				""";
		StreamEvent event = parse(data);
		assertNotNull(event);
		assertEquals(StreamEvent.Type.INCOMPLETE, event.type());
		assertEquals("max_output_tokens", event.response().incompleteDetails().reason());
	}

	@Test
	void parseFailedEvent_returnsErrorWithMessage() {
		String data = """
				{"type":"response.failed","response":{"id":"resp_3","status":"failed",
				"error":{"type":"server_error","message":"boom","code":"500"}}}
				""";
		StreamEvent event = parse(data);
		assertNotNull(event);
		assertEquals(StreamEvent.Type.ERROR, event.type());
		assertEquals("boom", event.errorMessage());
	}

	@Test
	void parseErrorEvent_returnsErrorWithMessage() {
		StreamEvent event = parse("{\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}");
		assertNotNull(event);
		assertEquals(StreamEvent.Type.ERROR, event.type());
		assertEquals("rate limited", event.errorMessage());
	}

	@Test
	void parseUnknownEventType_returnsNull() {
		// 未知事件类型（如 response.created）应静默忽略，保证向前兼容
		assertNull(parse("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_4\"}}"));
	}

	@Test
	void parseMalformedJson_returnsNull() {
		// 畸形 JSON 不应抛异常中断流，应按单条事件损坏跳过
		assertNull(parse("{not valid json"));
	}

	@Test
	void parseCompletedEventWithWrongStructure_returnsNull() {
		// response 字段结构不匹配时 convertValue 抛 IllegalArgumentException，
		// 必须被捕获并跳过，不能让一条畸形事件中断整个 SSE 流（回归用例）
		assertNull(parse("{\"type\":\"response.completed\",\"response\":\"not-an-object\"}"));
	}

	@Test
	void parseEventWithoutType_returnsNull() {
		assertNull(parse("{\"delta\":\"orphan\"}"));
	}

}
