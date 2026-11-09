package com.flj.AiService;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃回复在途注册表（ADR 0009）：按 userId 键控，per-user 单活跃流不变量的唯一事实源。
 * 职责三合一：新流守卫（tryClaim 原子抢占）、停止命令定位（requestStop）、活跃状态查询（findActive）。
 * 复用 ADR 0004 情绪分析已验证的 ConcurrentHashMap 并发去重模式。
 */
@Component
public class ActiveReplyRegistry {

    private final ConcurrentHashMap<Long, ActiveReplyHandle> activeReplies = new ConcurrentHashMap<>();

    /**
     * 原子抢占 per-user 槽位。已有活跃回复时返回 null（守卫拒绝）。
     * 初始化阶段同样持有槽位，由任务明确收尾；不能按初始化耗时驱逐仍在执行的任务。
     */
    public ActiveReplyHandle tryClaim(Long userId, Long sessionId) {
        return tryClaim(userId, sessionId, null, null);
    }

    public ActiveReplyHandle tryClaim(Long userId, Long sessionId, String clientRequestId, String fingerprint) {
        ActiveReplyHandle handle = new ActiveReplyHandle(sessionId);
        handle.bindClientRequest(clientRequestId, fingerprint);
        ActiveReplyHandle existing = activeReplies.putIfAbsent(userId, handle);
        return existing == null ? handle : null;
    }

    /**
     * 释放槽位（仅当仍是该句柄，防止误删后到的新流）。
     * 由生成任务统一终止路径调用，重复调用安全。
     */
    public void release(Long userId, ActiveReplyHandle handle) {
        if (userId != null && handle != null) {
            activeReplies.remove(userId, handle);
        }
    }

    /**
     * 显式停止当前活跃回复。返回被停止的会话 id；无活跃回复返回 null。
     * discard=true：停止且残句不落库（删除会话场景）。
     */
    public Long requestStop(Long userId, boolean discard) {
        ActiveReplyHandle handle = stopCurrent(userId, discard);
        return handle == null ? null : handle.getSessionId();
    }

    /** Returns exactly the handle receiving the command, even if a new task claims the slot. */
    public ActiveReplyHandle stopCurrent(Long userId, boolean discard) {
        ActiveReplyHandle handle = activeReplies.get(userId);
        if (handle == null) {
            return null;
        }
        handle.requestStop(discard);
        return handle;
    }

    /**
     * 若活跃回复属于指定会话则停止（删除会话场景：删除优先，残句不落库）。
     */
    public void stopIfSession(Long userId, Long sessionId) {
        ActiveReplyHandle handle = activeReplies.get(userId);
        if (handle != null && sessionId.equals(handle.getSessionId())) {
            handle.requestStop(true);
        }
    }

    /**
     * 活跃状态查询：供前端刷新后恢复输入锁定态、会话列表"生成中"徽标。
     */
    public ActiveReplyHandle findActive(Long userId) {
        return activeReplies.get(userId);
    }
}
