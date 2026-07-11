/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

/**
 * 会话记忆摘要服务接口。
 * <p>
 * 核心机制：对话超过一定轮数后，通过 LLM 将历史消息压缩为一段短文本摘要，
 * 加载时摘要作为 SYSTEM 消息插入历史列表头部，用几百字替代数百轮原始消息。
 */
public interface ConversationMemorySummaryService {

    /**
     * 异步检查是否需要压缩，仅在 assistant 消息写入时触发。
     */
    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    /**
     * 加载该会话的最新摘要记录，返回 SYSTEM 角色的 ChatMessage。
     */
    ChatMessage loadLatestSummary(String conversationId, String userId);

    /**
     * 将摘要内容包装为 {@code <conversation-summary>} 标签，便于 LLM 区分摘要与原始消息。
     */
    ChatMessage decorateIfNeeded(ChatMessage summary);
}
