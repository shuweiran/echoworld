package com.roleplay.engine.service;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.model.CompressedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P-0823-M：相关性检索接入角色上下文前的可见性与去重边界。 */
class MemoryStoreRelevantMemoryTest {

    @Test
    void retrievesOlderVisibleMemoryButExcludesRecentAndPrivateMessages() {
        MemoryStore store = new MemoryStore();
        store.createSession("memory-retrieval", List.of("A", "B"), Map.of());
        store.addMessage(new Message(Message.Role.AGENT, "A", "旧线索：仓库的红色钥匙藏在钟后。"));
        store.addMessage(new Message(Message.Role.AGENT, "B", "私密线索：仓库密码是 739。")
                .withVisibility("B"));
        for (int i = 0; i < 30; i++) {
            store.addMessage(new Message(Message.Role.AGENT, "A", "最近消息 " + i + "：仓库。"));
        }

        String context = store.getRelevantMemoryContext("A", "仓库钥匙", 3, 2, 30, false);

        assertTrue(context.contains("旧线索：仓库的红色钥匙"), "应召回短期窗口外、角色可见的旧线索");
        assertFalse(context.contains("私密线索"), "不得检索其他角色不可见的私密消息");
        assertFalse(context.contains("最近消息 29"), "最近窗口已由对话历史注入，不应重复召回");
    }

    @Test
    void compressedChunksAreOnlyIncludedWhenCallerAllowsIt() {
        MemoryStore store = new MemoryStore();
        store.createSession("memory-chunk", List.of("A"), Map.of());
        store.getSession().getCompressedChunks().add(new CompressedChunk(
                "chunk_1_5", 1, 5, "仓库钥匙曾被交给管家", List.of(), List.of(), 0.9));

        String hidden = store.getRelevantMemoryContext("A", "仓库钥匙", 3, 2, 30, false);
        String included = store.getRelevantMemoryContext("A", "仓库钥匙", 3, 2, 30, true);

        assertFalse(hidden.contains("相关记忆摘要"), "非公开轨道不得引入无可见性标记的压缩块");
        assertTrue(included.contains("仓库钥匙曾被交给管家"), "公开轨道可召回相关压缩摘要");
    }
}
