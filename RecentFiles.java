package net.timelegend.chaka.viewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers recently opened PDFs and the last-read page of each,
 * stored as a small JSON list in SharedPreferences (most recent first).
 */
public class RecentFiles
{
	private static final String PREFS = "chaka_recent";
	private static final String KEY = "files";
	private static final int MAX = 20;

	public static class Entry {
		public String uri;
		public String name;
		public int page;   // 0-based last-read page
		public int pages;  // total pages
		public long time;
	}

	public static synchronized List<Entry> load(Context c) {
		List<Entry> list = new ArrayList<>();
		try {
			String json = prefs(c).getString(KEY, null);
			if (json == null)
				return list;
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				Entry e = new Entry();
				e.uri = o.getString("uri");
				e.name = o.optString("name", e.uri);
				e.page = o.optInt("page", 0);
				e.pages = o.optInt("pages", 0);
				e.time = o.optLong("time", 0);
				list.add(e);
			}
		} catch (Exception x) {
			Tool.e("recent load: " + x);
		}
		return list;
	}

	private static void store(Context c, List<Entry> list) {
		try {
			JSONArray arr = new JSONArray();
			for (Entry e : list) {
				JSONObject o = new JSONObject();
				o.put("uri", e.uri);
				o.put("name", e.name);
				o.put("page", e.page);
				o.put("pages", e.pages);
				o.put("time", e.time);
				arr.put(o);
			}
			prefs(c).edit().putString(KEY, arr.toString()).apply();
		} catch (Exception x) {
			Tool.e("recent store: " + x);
		}
	}

	public static synchronized Entry find(Context c, String uri) {
		for (Entry e : load(c))
			if (e.uri.equals(uri))
				return e;
		return null;
	}

	/** Add or update a file, moving it to the top of the list. */
	public static synchronized void record(Context c, String uri, String name, int page, int pages) {
		List<Entry> list = load(c);
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).uri.equals(uri)) {
				list.remove(i);
				break;
			}
		}
		Entry e = new Entry();
		e.uri = uri;
		e.name = name;
		e.page = page;
		e.pages = pages;
		e.time = System.currentTimeMillis();
		list.add(0, e);
		while (list.size() > MAX)
			list.remove(list.size() - 1);
		store(c, list);
	}

	public static synchronized void remove(Context c, String uri) {
		List<Entry> list = load(c);
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).uri.equals(uri)) {
				list.remove(i);
				break;
			}
		}
		store(c, list);
	}

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
