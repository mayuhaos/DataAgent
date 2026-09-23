/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @description 图搜索服务，处理与后端的流式 (SSE) 交互，实现搜索过程的实时反馈
 */

import axios from 'axios';
import { buildApiUrl } from '~/utils/api';

export interface GraphRequest {
	agentId: string;
	/** Stable chat session. Kept across ordinary follow-up questions. */
	sessionId?: string;
	threadId?: string;
	query: string;
	humanFeedback: boolean;
	humanFeedbackContent?: string;
	clarificationAnswer?: string;
	resumeMode?: 'clarification' | 'human_feedback' | null;
	reconnect?: boolean;
	lastSequence?: number;
	rejectedPlan: boolean;
	nl2sqlOnly: boolean;
	thinkingEnabled?: boolean;
	reasoningEffort?: 'high' | 'max';
}

export interface GraphNodeResponse {
	agentId: string;
	threadId: string;
	nodeName: string;
	textType: TextType;
	text: string;
	sequence?: number;
	workflowStartedAt?: number;
	nodeStartedAt?: number;
	nodeElapsedMs?: number;
	totalElapsedMs?: number;
	timingOnly?: boolean;
	interactionType?: 'normal' | 'clarification';
	awaitingInput?: boolean;
	error: boolean;
	complete: boolean;
	retrying?: boolean;
	retryCount?: number;
	errorMessage?: string;
}

export enum TextType {
	JSON = 'JSON',
	PYTHON = 'PYTHON',
	SQL = 'SQL',
	HTML = 'HTML',
	MARK_DOWN = 'MARK_DOWN',
	RESULT_SET = 'RESULT_SET',
	FINAL_ANSWER = 'FINAL_ANSWER',
	TEXT = 'TEXT',
}

const API_BASE_URL = '/api';

class GraphService {
	async streamConversation(
		sessionId: string,
		userMessage: string,
		onMessage: (response: GraphNodeResponse) => Promise<void>,
		onError?: (error: Error) => Promise<void>,
		onComplete?: () => Promise<void>,
	): Promise<() => void> {
		const controller = new AbortController();
		let closedIntentionally = false;
		void (async () => {
			try {
				const response = await fetch(
					buildApiUrl(`${API_BASE_URL}/conversations/${sessionId}/messages`),
					{
						method: 'POST',
						headers: {
							'Content-Type': 'application/json',
							Accept: 'text/event-stream',
						},
						body: JSON.stringify({ userMessage }),
						signal: controller.signal,
					},
				);
				if (!response.ok || !response.body) {
					throw new Error(`Conversation request failed (${response.status})`);
				}
				const reader = response.body.getReader();
				const decoder = new TextDecoder();
				let buffer = '';
				while (true) {
					const { done, value } = await reader.read();
					if (done) break;
					buffer += decoder.decode(value, { stream: true });
					const events = buffer.split(/\r?\n\r?\n/);
					buffer = events.pop() || '';
					for (const event of events) {
						const data = event
							.split(/\r?\n/)
							.filter((line) => line.startsWith('data:'))
							.map((line) => line.slice(5).trimStart())
							.join('\n');
						if (data) await onMessage(JSON.parse(data) as GraphNodeResponse);
					}
				}
				if (!closedIntentionally && onComplete) await onComplete();
			} catch (error) {
				if (!closedIntentionally && onError) {
					await onError(
						error instanceof Error
							? error
							: new Error('Conversation stream failed'),
					);
				}
			}
		})();
		return () => {
			closedIntentionally = true;
			controller.abort();
		};
	}

	async streamSearch(
		request: GraphRequest,
		onMessage: (response: GraphNodeResponse) => Promise<void>,
		onError?: (error: Error) => Promise<void>,
		onComplete?: () => Promise<void>,
	): Promise<() => void> {
		const params = new URLSearchParams();
		params.append('agentId', request.agentId);
		if (request.sessionId) params.append('sessionId', request.sessionId);
		if (request.threadId) params.append('threadId', request.threadId);
		params.append('query', request.query);
		params.append('humanFeedback', request.humanFeedback.toString());
		params.append('rejectedPlan', request.rejectedPlan.toString());
		params.append('nl2sqlOnly', request.nl2sqlOnly.toString());
		if (request.thinkingEnabled !== undefined) {
			params.append('thinkingEnabled', request.thinkingEnabled.toString());
		}
		if (request.reasoningEffort) {
			params.append('reasoningEffort', request.reasoningEffort);
		}
		if (request.humanFeedbackContent) {
			params.append('humanFeedbackContent', request.humanFeedbackContent);
		}
		if (request.clarificationAnswer) {
			params.append('clarificationAnswer', request.clarificationAnswer);
		}
		if (request.resumeMode) {
			params.append('resumeMode', request.resumeMode);
		}
		if (request.reconnect) {
			params.append('reconnect', 'true');
		}
		if (request.lastSequence !== undefined) {
			params.append('lastSequence', String(request.lastSequence));
		}

		const url = buildApiUrl(
			`${API_BASE_URL}/stream/search?${params.toString()}`,
		);
		const eventSource = new EventSource(url);

		let isCompleted = false;
		let isFailed = false;
		let isClosedIntentionally = false;

		eventSource.onmessage = async (event) => {
			try {
				const nodeResponse: GraphNodeResponse = JSON.parse(event.data);
				await onMessage(nodeResponse);
			} catch (parseError) {
				console.error('Failed to parse SSE data:', parseError);
				if (onError) {
					await onError(new Error('Failed to parse server response'));
				}
			}
		};

		// The backend sends terminal failures as a named SSE event. Handle it
		// separately from EventSource's connection-level onerror callback so the
		// server-provided error message is not lost.
		eventSource.addEventListener('error', async (event) => {
			if (isCompleted || isFailed || isClosedIntentionally) return;
			if (!(event instanceof MessageEvent) || !event.data) return;
			isFailed = true;
			eventSource.close();
			try {
				const response = JSON.parse(event.data) as GraphNodeResponse;
				if (onError)
					await onError(new Error(response.text || 'Stream processing failed'));
			} catch {
				if (onError) await onError(new Error('Stream processing failed'));
			}
		});

		eventSource.onerror = async (errorEvent) => {
			if (isCompleted || isFailed || isClosedIntentionally) {
				return;
			}
			if (eventSource.readyState === EventSource.CLOSED) {
				return;
			}

			isFailed = true;
			eventSource.close();

			let streamError = new Error('Stream connection failed');
			if (errorEvent instanceof MessageEvent && errorEvent.data) {
				try {
					const response = JSON.parse(errorEvent.data) as GraphNodeResponse;
					streamError = new Error(response.text || 'Stream processing failed');
				} catch (parseError) {
					console.error('Failed to parse SSE error data:', parseError);
				}
			}

			console.error('EventSource error:', errorEvent);
			if (onError) {
				await onError(streamError);
			}
		};

		eventSource.addEventListener('complete', async (event) => {
			isCompleted = true;
			eventSource.close();
			if (event instanceof MessageEvent && event.data) {
				try {
					await onMessage(JSON.parse(event.data) as GraphNodeResponse);
				} catch (parseError) {
					console.error('Failed to parse SSE completion data:', parseError);
				}
			}
			if (onComplete) {
				await onComplete();
			}
		});

		return () => {
			isClosedIntentionally = true;
			eventSource.close();
		};
	}

	async stopStream(threadId?: string, sessionId?: string): Promise<void> {
		await axios.post(buildApiUrl(`${API_BASE_URL}/stream/search/stop`), null, {
			params: { threadId, sessionId },
		});
	}
}

export default new GraphService();
