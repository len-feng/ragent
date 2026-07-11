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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息反馈（点赞/点踩）服务实现。
 * <p>
 * <b>两条写入路径：</b>
 * <ul>
 *   <li><b>异步</b>：Controller → submitFeedbackAsync → RocketMQ → Consumer → submitFeedbackByEvent → DB。
 *       用户侧 API 走此路径，削峰解耦。</li>
 *   <li><b>同步</b>：submitFeedback → 直接写库。内部管理后台等不需要 MQ 的场景使用。</li>
 * </ul>
 * <p>
 * <b>Upsert 逻辑：</b>
 * 无记录则 INSERT，有记录则 UPDATE ... WHERE updateTime < submitTime。
 * submitTime 是用户点击那一刻的客户端时间戳，这个条件保证了：
 * 即使 MQ 乱序消费（先发的消息后到），旧事件也无法覆盖新事件的写入结果。
 * <p>
 * <b>校验：</b>
 * vote 只能是 1（赞）或 -1（踩），且只能对 assistant 消息反馈。
 */
@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    private final MessageFeedbackMapper feedbackMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final MessageQueueProducer messageQueueProducer;

    @Value("message-feedback_topic${unique-name:}")
    private String feedbackTopic;

    @Override
    public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .vote(vote)
                .reason(request.getReason())
                .comment(request.getComment())
                .submitTime(System.currentTimeMillis())
                .build();
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "消息反馈", event);
    }

    @Override
    public void cancelFeedbackAsync(String messageId) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .cancelled(true)
                .submitTime(System.currentTimeMillis())
                .build();
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "取消消息反馈", event);
    }

    @Override
    public void submitFeedback(String messageId, MessageFeedbackRequest request) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));

        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(messageId, userId, message.getConversationId(),
                vote, request.getReason(), request.getComment(), System.currentTimeMillis());
    }

    @Override
    public Map<String, Integer> getUserVotes(String userId, List<String> messageIds) {
        if (StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        List<MessageFeedbackDO> records = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
                        .in(MessageFeedbackDO::getMessageId, messageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyMap();
        }
        return records.stream()
                .collect(Collectors.toMap(
                        MessageFeedbackDO::getMessageId,
                        MessageFeedbackDO::getVote,
                        (first, second) -> first
                ));
    }

    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        Assert.isTrue("assistant".equalsIgnoreCase(message.getRole()), () -> new ClientException("仅支持对助手消息反馈"));
        return message;
    }

    // Upsert：无则插入，有则仅当 submitTime 晚于上次更新时间才覆盖（防 MQ 乱序写入旧数据）
    private void doUpsertFeedback(String messageId, String userId, String conversationId,
                                  Integer vote, String reason, String comment, long submitTime) {
        MessageFeedbackDO existing = feedbackMapper.selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, messageId)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
        );

        if (existing == null) {
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(messageId)
                    .conversationId(conversationId)
                    .userId(userId)
                    .vote(vote)
                    .reason(reason)
                    .comment(comment)
                    .build();
            feedbackMapper.upsertActiveFeedback(feedback);
        } else {
            // 仅当本次提交时间晚于记录最后更新时间时才覆盖，避免多节点并行消费乱序
            feedbackMapper.update(
                    MessageFeedbackDO.builder()
                            .vote(vote)
                            .reason(reason)
                            .comment(comment)
                            .build(),
                    Wrappers.lambdaUpdate(MessageFeedbackDO.class)
                            .eq(MessageFeedbackDO::getId, existing.getId())
                            .lt(MessageFeedbackDO::getUpdateTime, new Date(submitTime))
            );
        }
    }

    @Override
    public void submitFeedbackByEvent(MessageFeedbackEvent event) {
        String messageId = event.getMessageId();
        String userId = event.getUserId();
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notBlank(userId, () -> new ClientException("用户ID不能为空"));
        if (event.isCancelled()) {
            ConversationMessageDO message = loadAssistantMessage(messageId, userId);
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(messageId)
                    .conversationId(message.getConversationId())
                    .userId(userId)
                    .build();
            feedbackMapper.upsertCancelledFeedback(feedback);
            return;
        }

        Assert.notNull(event.getVote(), () -> new ClientException("反馈值不能为空"));
        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(
                messageId,
                userId,
                message.getConversationId(),
                event.getVote(),
                event.getReason(),
                event.getComment(),
                event.getSubmitTime())
        ;
    }
}
