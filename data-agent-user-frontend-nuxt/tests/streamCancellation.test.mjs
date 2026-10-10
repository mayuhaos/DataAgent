import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import ts from 'typescript';

const require = createRequire(import.meta.url);
const pending = () => new Promise(() => {});
const ref = (value) => ({ value });

function load(relativePath, mocks = {}, globals = {}) {
	const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
	const code = ts.transpileModule(source, {
		compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
	}).outputText;
	const exports = {};
	new Function('require', 'exports', ...Object.keys(globals), code)(
		(name) => name in mocks ? mocks[name] : require(name),
		exports, ...Object.values(globals),
	);
	return exports;
}

for (const hasTransport of [false, true]) {
	test(`Stop immediately clears state with transport=${hasTransport} and hanging network`, async () => {
		let closed = 0;
		let cancelledSession;
		let persistedCleared = false;
		const state = {
			isStreaming: true, isReportStreaming: true, nodeBlocks: [],
			markdownReportContent: 'partial', lastRequest: null,
			closeStream: hasTransport ? () => { closed++; } : null,
		};
		const manager = {
			getSessionState: () => state,
			clearPersistedSessionState: () => { persistedCleared = true; },
		};
		const { useChatStore } = load('../app/stores/chat.ts', {
			pinia: { defineStore: (_, setup) => setup },
			'~/services/chat/index': { default: { saveMessage: pending } },
			'~/services/graph/index': {
				default: { stopStream: (_, session) => { cancelledSession = session; return pending(); } },
			},
			'~/services/sessionStateManager/index': { useSessionStateManager: () => manager },
			'~/services/agentDatasource/index': {},
			'~/services/modelConfig/index': {},
			'~/services/datasource/index': {},
			'~/utils/api': {},
		}, { ref });
		const store = useChatStore();
		store.currentSession.value = { id: 'restored-session' };
		store.isStreaming.value = true;
		store.isReportStreaming.value = true;
		await store.stopStreaming();
		assert.equal(store.isStreaming.value, false);
		assert.equal(store.isReportStreaming.value, false);
		assert.equal(state.closeStream, null);
		assert.equal(state.isStreaming, false);
		assert.equal(closed, hasTransport ? 1 : 0);
		assert.equal(cancelledSession, 'restored-session');
		assert.equal(persistedCleared, true);
		await store.stopStreaming();
		assert.equal(store.currentMessages.value.length, 1);
	});
}

function loadGraph(globals = {}, axios = {}) {
	return load('../app/services/graph/index.ts', {
		axios: { default: axios },
		'~/utils/api': { buildApiUrl: (path) => path },
	}, globals).default;
}

test('Backend-persisted clarification is not saved again by the frontend', async () => {
	let callbacks;
	let saves = 0;
	const persisted = { id: 42, role: 'assistant', messageType: 'clarification', content: 'Retry' };
	const state = { nodeBlocks: [], lastRequest: null, closeStream: null };
	const { useChatStore } = load('../app/stores/chat.ts', {
		pinia: { defineStore: (_, setup) => setup },
		'~/services/chat/index': { default: {
			saveMessage: async () => { saves++; },
			getSessionMessages: async () => [persisted],
		} },
		'~/services/graph/index': {
			TextType: { FINAL_ANSWER: 'FINAL_ANSWER' },
			default: { streamConversation: async (_, __, onMessage, onError, onComplete) => {
				callbacks = { onMessage, onComplete };
				return () => {};
			} },
		},
		'~/services/sessionStateManager/index': { useSessionStateManager: () => ({
			getSessionState: () => state,
			persistSessionState: () => {}, clearPersistedSessionState: () => {},
		}) },
		'~/services/agentDatasource/index': {},
		'~/services/modelConfig/index': {},
		'~/services/datasource/index': {},
		'~/utils/api': {},
	}, { ref });
	const store = useChatStore();
	store.currentSession.value = { id: 'session', title: 'Test' };
	await store.sendMessage('Question');
	await callbacks.onMessage({ nodeName: 'ConversationPlan', threadId: 'thread', text: '{"status":"CLARIFICATION"}' });
	// Cover an older backend that still emits the already-persisted final text.
	await callbacks.onMessage({ nodeName: 'ConversationEdit', textType: 'FINAL_ANSWER', text: 'Retry' });
	await callbacks.onComplete();
	assert.equal(saves, 0);
	assert.deepEqual(store.currentMessages.value, [persisted]);
	assert.equal(store.isStreaming.value, false);
	assert.equal(store.awaitingClarification.value, false);
});

for (const terminal of ['complete', 'error']) {
	test(`SSE ${terminal} finishes without waiting for HTTP EOF`, async () => {
		let readerCancelled = false;
		let reads = 0;
		const reader = {
			read: () => ++reads === 1 ? Promise.resolve({
				done: false,
				value: new TextEncoder().encode(`event: ${terminal}\ndata: ${JSON.stringify({
					complete: terminal === 'complete', error: terminal === 'error', text: 'failure',
				})}\n\n`),
			}) : pending(),
			cancel: async () => { readerCancelled = true; },
		};
		const graph = loadGraph({ fetch: async () => ({ ok: true, body: { getReader: () => reader } }) });
		const result = new Promise((resolve) => {
			void graph.streamConversation('session', 'query', async () => {},
				async (error) => resolve(error.message), async () => resolve('completed'));
		});
		assert.equal(await result, terminal === 'complete' ? 'completed' : 'failure');
		assert.equal(readerCancelled, true);
		assert.equal(reads, 1);
	});
}

test('Stop request has a bounded timeout and session fallback', async () => {
	let options;
	const graph = loadGraph({}, { post: async (_, __, config) => { options = config; } });
	await graph.stopStream(undefined, 'session');
	assert.equal(options.timeout, 5000);
	assert.equal(options.params.sessionId, 'session');
});
