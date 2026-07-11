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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 名词归一化服务，RAG 管道中最前置的步骤。
 * <p>
 * <b>目的：</b>将用户口语化的别名/简称替换为系统中的标准名称，
 * 让后续的 LLM 改写和向量检索都基于标准化术语进行。
 * <p>
 * <b>示例：</b>"平安保司" → "平安保险公司"，"深分" → "深圳分公司"
 * <p>
 * <b>匹配规则来自 DB，排序后缓存到 Redis（7天TTL）：</b>
 * <ol>
 *   <li>按 priority 降序 → 高优先级规则先执行</li>
 *   <li>同 priority 按 sourceTerm 长度降序 → 长词优先匹配，避免短词截断长词前缀</li>
 * </ol>
 * <p>
 * <b>替换逻辑（QueryTermMappingUtil.applyMapping）：</b>
 * 对每个规则在文本中 indexOf 查找 sourceTerm，替换为 targetTerm。
 * 如果当前位置已经是 targetTerm 开头，则跳过不替换（防止重复替换，
 * 比如"平安保险公司"中的"保险"不应该被另一个规则再替换一次）。
 * <p>
 * <b>只在 pipeline 的第一阶段被调用：</b>MultiQuestionRewriteService.rewriteWithSplit()，
 * 先 normalize 再传归一化后的文本给 LLM 改写。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryTermMappingService {
    private final QueryTermMappingMapper mappingMapper;
    private final QueryTermMappingCacheManager cacheManager;

    /**
     * 对用户问题做术语归一化
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        List<QueryTermMappingDO> mappings = loadMappings();
        if (mappings.isEmpty()) {
            return text;
        }

        String result = text;
        for (QueryTermMappingDO mapping : mappings) {
            // DB 查询时已过滤 enabled=1，但缓存中的数据可能在新规则启用前短暂残留，此处做二次兜底
            if (mapping.getEnabled() == null || mapping.getEnabled() == 0) {
                continue;
            }
            // matchType=1 为精确匹配，其他类型（如正则、模糊）暂不在此处处理
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                continue;
            }
            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}'", text, result);
        }
        return result;
    }

    /**
     * 加载映射规则：优先从 Redis 缓存读取，缓存未命中则从数据库加载并回填缓存。
     * <p>
     * 注意：getMappingsFromCache() 在 Redis key 不存在时返回 null，在缓存为空数组时返回空列表。
     * 此处用 isNotEmpty 判断，空列表会被视为未命中，在 DB 无规则时会反复穿透到数据库，
     * 但不会造成功能问题——每次穿透查到的也是空结果。
     */
    private List<QueryTermMappingDO> loadMappings() {
        List<QueryTermMappingDO> cached = cacheManager.getMappingsFromCache();
        // 一条规则都没配也要认缓存，否则每次提问都会白读一次数据库，规则增删改各自会清缓存
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，从数据库加载
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        // 按优先级降序排列，同优先级按源词长度降序——长词优先匹配，避免短词截断长词
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );

        // 回填 Redis 缓存
        cacheManager.saveMappingsToCache(dbList);
        log.info("术语映射规则从数据库加载完成，共 {} 条规则", dbList.size());
        return dbList;
    }
}
