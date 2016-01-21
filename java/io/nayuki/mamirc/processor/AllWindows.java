package io.nayuki.mamirc.processor;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import io.nayuki.mamirc.processor.Window.Flags;


// Note: All reading/writing must be performed while holding the MamircProcessor lock.
final class AllWindows {
	
	/*---- Base functionality ----*/
	
	private final MamircProcessor master;
	public final Map<String,Map<String,Window>> windows;
	
	
	
	public AllWindows(MamircProcessor master) {
		if (master == null)
			throw new NullPointerException();
		this.master = master;
		windows = new TreeMap<>();
	}
	
	
	
	// Used by the big set of public methods at the bottom.
	private void addLine(String profile, String party, int flags, long timestamp, Object... payload) {
		timestamp = divideAndFloor(timestamp, 1000);
		Window win = getWindow(profile, party);
		win.addLine(flags, timestamp, payload);
		List<Window.Line> list = win.lines;
		if (list.size() - 100 >= 10000)
			list.subList(0, 100).clear();
		master.addWindowUpdate(profile, party, win.nextSequence, flags, timestamp, payload);
	}
	
	
	public boolean openWindow(String profile, String party) {
		Map<String,Window> innerMap = windows.get(profile);
		if (innerMap == null || !innerMap.containsKey(party)) {
			getWindow(profile, party);
			return true;
		} else
			return false;
	}
	
	
	public boolean closeWindow(String profile, String party) {
		Map<String,Window> innerMap = windows.get(profile);
		return innerMap != null && innerMap.remove(party) != null;
	}
	
	
	// Creates or retrieves the given window. Result is not null.
	public Window getWindow(String profile, String party) {
		if (!windows.containsKey(profile))
			windows.put(profile, new CaseInsensitiveTreeMap<Window>());
		Map<String,Window> innerMap = windows.get(profile);
		if (!innerMap.containsKey(party))
			innerMap.put(party, new Window());
		return innerMap.get(party);
	}
	
	
	private static long divideAndFloor(long x, long y) {
		long z = x / y;
		if (((x >= 0) ^ (y >= 0)) && z * y != x)
			z--;
		return z;
	}
	
	
	
	/*---- Line types ----*/
	
	public void addConnectingLine(String profile, long timestamp, String hostname, int port, boolean ssl) {
		addLine(profile, "", Flags.CONNECTING.value, timestamp, hostname, port, ssl);
	}
	
	public void addConnectedLine(String profile, long timestamp, String ipAddr) {
		addLine(profile, "", Flags.CONNECTED.value, timestamp, ipAddr);
	}
	
	public void addDisconnectedLine(String profile, String party, long timestamp) {
		addLine(profile, party, Flags.DISCONNECTED.value, timestamp);
	}
	
	public void addInitNoTopicLine(String profile, String party, long timestamp) {
		addLine(profile, party, Flags.INITNOTOPIC.value, timestamp);
	}
	
	public void addInitTopicLine(String profile, String party, long timestamp, String text) {
		addLine(profile, party, Flags.INITTOPIC.value, timestamp, text);
	}
	
	public void addJoinLine(String profile, String party, long timestamp, String nick) {
		addLine(profile, party, Flags.JOIN.value, timestamp, nick);
	}
	
	public void addKickLine(String profile, String party, long timestamp, String kickee, String kicker, String text) {
		addLine(profile, party, Flags.KICK.value, timestamp, kickee, kicker, text);
	}
	
	public void addModeLine(String profile, String party, long timestamp, String source, String text) {
		addLine(profile, party, Flags.MODE.value, timestamp, source, text);
	}
	
	public void addNamesLine(String profile, String party, long timestamp, String[] names) {
		addLine(profile, party, Flags.NAMES.value, timestamp, (Object[])names);
	}
	
	public void addNickLine(String profile, String party, long timestamp, String oldNick, String newNick) {
		addLine(profile, party, Flags.NICK.value, timestamp, oldNick, newNick);
	}
	
	public void addNoticeLine(String profile, String party, int flags, long timestamp, String nick, String text) {
		addLine(profile, party, Flags.NOTICE.value | flags, timestamp, nick, text);
	}
	
	public void addPartLine(String profile, String party, long timestamp, String nick) {
		addLine(profile, party, Flags.PART.value, timestamp, nick);
	}
	
	public void addPrivmsgLine(String profile, String party, int flags, long timestamp, String nick, String text) {
		addLine(profile, party, Flags.PRIVMSG.value | flags, timestamp, nick, text);
	}
	
	public void addTopicLine(String profile, String party, long timestamp, String nick, String text) {
		addLine(profile, party, Flags.TOPIC.value, timestamp, nick, text);
	}
	
	public void addQuitLine(String profile, String party, long timestamp, String nick, String text) {
		addLine(profile, party, Flags.QUIT.value, timestamp, nick, text);
	}
	
	public void addServerReplyLine(String profile, long timestamp, String code, String text) {
		addLine(profile, "", Flags.SERVERREPLY.value, timestamp, code, text);
	}
	
}
