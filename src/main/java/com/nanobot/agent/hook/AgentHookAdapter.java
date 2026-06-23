package com.nanobot.agent.hook;

/**
 * AgentHook 适配器抽象类，提供 reraise 构造函数。
 * 对标 Python: {@code nanobot/agent/hook.py:47-51 class AgentHook.__init__(self, reraise=False)}
 *
 * <p>Python 中 AgentHook 是一个类，__init__ 中存储 {@code self._reraise} 实例字段。
 * Java 中 AgentHook 是接口，此类为需要 reraise 模式的实现提供基础。
 */
public abstract class AgentHookAdapter implements AgentHook {

    private final boolean reraise;

    /**
     * 构造适配器并设置 reraise 模式。
     *
     * @param reraise true 时 hook 链中的异常会向上传播，false 时静默吞下
     */
    // 对标 Python hook.py:50-51 __init__(self, reraise=False)
    protected AgentHookAdapter(boolean reraise) {
        this.reraise = reraise;
    }

    @Override
    public boolean reraise() {
        return reraise;
    }
}
