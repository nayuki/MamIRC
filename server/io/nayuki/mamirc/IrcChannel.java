package io.nayuki.mamirc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


final class IrcChannel {
	
	public Optional<String> topic = Optional.empty();
	public Optional<String> topicSetter = Optional.empty();
	public Optional<Long> topicTimestamp = Optional.empty();
	
	public Map<String,User> users = new HashMap<>();
	public Map<String,User> namesAccumulator = new HashMap<>();
	
	
	
	public static final class User {
		
		public Set<String> modes = new HashSet<>();
		
	}

	
}
