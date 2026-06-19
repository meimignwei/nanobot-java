package com.nanobot.agent.channel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ChannelManager {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    public void register(Channel channel) {
        channels.put(channel.name(), channel);
    }

    public Optional<Channel> get(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    public void unregister(String name) {
        channels.remove(name);
    }

    public List<String> enabledChannels() {
        return List.copyOf(channels.keySet());
    }
}
