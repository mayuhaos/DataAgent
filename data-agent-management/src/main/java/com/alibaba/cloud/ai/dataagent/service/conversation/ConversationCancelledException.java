package com.alibaba.cloud.ai.dataagent.service.conversation;

/** Causes the submission transaction to roll back after a stop request. */
public class ConversationCancelledException extends RuntimeException {

	public ConversationCancelledException() { super("任务已停止，未发布结果。"); }
}
