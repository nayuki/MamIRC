package io.nayuki.mamirc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


final class IrcChannel {
	
	public Optional<String> topic = Optional.empty();
	public Optional<String> topicSetter = Optional.empty();
	public Optional<Long> topicTimestamp = Optional.empty();
	
	public Map<String,IrcUser> users = new HashMap<>();
	public Map<String,IrcUser> namesAccumulator = new HashMap<>();
	
}
