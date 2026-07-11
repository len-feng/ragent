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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rerank 路由服务，和 Chat/Embedding 相同的路由模式。
 * <p>
 * <b>Rerank 与 ANN 的本质区别：</b>
 * <ul>
 *   <li>ANN：向量空间余弦距离，bi-encoder，速度快但只比较向量对齐，属于"粗筛"</li>
 *   <li>Rerank：cross-encoder 全文语义匹配，精度高但每个 (query, chunk) 对都要算，属于"精排"</li>
 * </ul>
 * <p>
 * 路由策略：通过 {@link ModelRoutingExecutor} 遍历候选 Rerank 模型，
 * 失败自动 fallback 到下一个备选。和 Chat/Embedding 共用同一套健康检查 + 熔断机制。
 */
@Service
@Primary
public class RoutingRerankService implements RerankService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, RerankClient> clientsByProvider;

    public RoutingRerankService(ModelSelector selector, ModelRoutingExecutor executor, List<RerankClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return executor.executeWithFallback(
                ModelCapability.RERANK,
                selector.selectRerankCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.rerank(query, candidates, topN, target)
        );
    }
}
