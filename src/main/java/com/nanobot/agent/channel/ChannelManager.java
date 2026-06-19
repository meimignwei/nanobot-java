package com.nanobot.agent.channel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 渠道管理器，负责渠道注册、查找、注销。
 * 对应 Python ChannelManager 类（channel/manager.py）。
 */
public class ChannelManager {

    /** 渠道注册表，key 为渠道名称 */
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    /** 注册渠道。
     *  对应 Python ChannelManager.register()。 */
    public void register(Channel channel) {
        channels.put(channel.name(), channel);
    }

    /** 按名称查找渠道。
     *  对应 Python ChannelManager.get()。 */
    public Optional<Channel> get(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    /** 注销渠道。
     *  对应 Python ChannelManager.unregister()。 */
    public void unregister(String name) {
        channels.remove(name);
    }

    /** 返回已注册渠道名称列表。
     *  对应 Python ChannelManager.enabled_channels()。 */
    public List<String> enabledChannels() {
        return List.copyOf(channels.keySet());
    }
}
