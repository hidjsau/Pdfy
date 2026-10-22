package net.timelegend.chaka.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.DisplayList;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Location;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.RectI;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF engine wrapper: opens the document, renders pages to bitmaps
 * and gives access to the structured text used by text selection.
 */
public class MuPDFCore
{
	private static final int RESOLUTION = 160;

	private Document doc;
	private int pageCount = -1;
	private int currentPage = -1;
	private Page page;
	private float pageWidth;
	private float pageHeight;
	private DisplayList displayList;
	private final SparseArray<TextSelectionModel> tsModel = new SparseArray<>();

	/* Default to "A Format" pocket book size (only matters for reflowable docs). */
	private static final int LAYOUT_W = 312;
	private static final int LAYOUT_H = 504;
	private static final int LAYOUT_EM = 10;

	private MuPDFCore(Document doc) {
		this.doc = doc;
		if (!doc.needsPassword()) setup();
	}

	private void setup() {
		doc.layout(LAYOUT_W, LAYOUT_H, LAYOUT_EM);
		pageCount = doc.countPages();
		currentPage = -1;
	}

	public MuPDFCore(byte buffer[], String magic) {
		this(Document.openDocument(buffer, magic));
	}

	public MuPDFCore(SeekableInputStream stm, String magic) {
		this(Document.openDocument(stm, magic));
	}

	public int countPages() {
		return pageCount;
	}

	public synchronized boolean needsPassword() {
		return doc.needsPassword();
	}

	public synchronized boolean authenticatePassword(String password) {
		boolean authenticated = doc.authenticatePassword(password);
		if (authenticated) setup();
		return authenticated;
	}

	private synchronized void gotoPage(int pageNum) {
		if (pageNum > pageCount - 1)
			pageNum = pageCount - 1;
		else if (pageNum < 0)
			pageNum = 0;
		if (pageNum != currentPage) {
			if (page != null)
				page.destroy();
			page = null;
			if (displayList != null)
				displayList.destroy();
			displayList = null;
			pageWidth = 0;
			pageHeight = 0;

			if (doc != null) {
				page = doc.loadPage(pageNum);
				Rect b = page.getBounds();
				pageWidth = b.x1 - b.x0;
				pageHeight = b.y1 - b.y0;
			}
			currentPage = pageNum;
		}
	}

	/* page size in PDF units: x = width, y = height */
	public synchronized PointF getPageSize(int pageNum) {
		gotoPage(pageNum);
		return new PointF(pageWidth, pageHeight);
	}

	public synchronized void onDestroy() {
		if (displayList != null)
			displayList.destroy();
		displayList = null;
		if (page != null)
			page.destroy();
		page = null;
		if (doc != null)
			doc.destroy();
		doc = null;
	}

	public synchronized void drawPage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		gotoPage(pageNum);

		if (displayList == null && page != null)
			try {
				displayList = page.toDisplayList();
			} catch (Exception ex) {
				displayList = null;
			}

		if (displayList == null || page == null)
			return;

		float zoom = RESOLUTION / 72 / 2;
		Matrix ctm = new Matrix(zoom, zoom);
		RectI bbox = new RectI(page.getBounds().transform(ctm));
		float xscale = (float)pageW / (float)(bbox.x1 - bbox.x0);
		float yscale = (float)pageH / (float)(bbox.y1 - bbox.y0);
		ctm.scale(xscale, yscale);

		AndroidDrawDevice dev = null;
		try {
			dev = new AndroidDrawDevice(bm, patchX, patchY);
			displayList.run(dev, ctm, cookie);
			dev.close();
		} finally {
			if (dev != null) dev.destroy();
		}
	}

	/* ---- table of contents ---- */

	public static class OutlineItem {
		public String title;
		public int page;   // 0-based, -1 if the entry has no resolvable target
		public int level;  // nesting depth, 0 = top level
	}

	/** Flattened table of contents (empty if the document has none). */
	public synchronized ArrayList<OutlineItem> getOutline() {
		ArrayList<OutlineItem> list = new ArrayList<>();
		if (doc == null || pageCount <= 0)
			return list;
		try {
			Outline[] top = doc.loadOutline();
			if (top != null)
				flattenOutline(top, 0, list);
		} catch (Throwable t) {
			Tool.e("outline: " + t);
		}
		return list;
	}

	private void flattenOutline(Outline[] items, int level, List<OutlineItem> out) {
		for (Outline o : items) {
			if (o == null)
				continue;
			OutlineItem it = new OutlineItem();
			it.title = o.title != null ? o.title.trim() : "";
			it.level = level;
			it.page = -1;
			try {
				if (o.uri != null) {
					Location loc = doc.resolveLink(o.uri);
					it.page = doc.pageNumberFromLocation(loc);
				}
			} catch (Throwable t) {
				it.page = -1;
			}
			if (it.page >= pageCount)
				it.page = -1;
			if (it.title.length() > 0)
				out.add(it);
			if (o.down != null)
				flattenOutline(o.down, level + 1, out);
		}
	}

	public synchronized StructuredText getSText(int pageNum) {
		gotoPage(pageNum);
		return page != null ? page.toStructuredText() : null;
	}

	/* ---- text selection state, kept per page ---- */

	public TextSelectionModel getTSModel(int pageNum) {
		return getTSModel(pageNum, false);
	}

	public TextSelectionModel getTSModel(int pageNum, boolean create) {
		TextSelectionModel tsmodel = tsModel.get(pageNum);
		if (tsmodel == null) {
			if (create) {
				tsmodel = new TextSelectionModel();
				tsmodel.sText = getSText(pageNum);
			}
		}
		return tsmodel;
	}

	/* ---- "select multiple": selections kept while more are added ---- */

	public static class MultiItem {
		public int page;
		public Quad[] boxes;
		public String text;
	}

	private final ArrayList<MultiItem> multi = new ArrayList<>();

	/** Keep the page's current selection. Returns false if there is nothing new to keep. */
	public boolean commitSelection(int page, String text) {
		TextSelectionModel m = tsModel.get(page);
		if (m == null || m.selectionBoxes == null || m.selectionBoxes.length == 0 || text == null || text.length() == 0)
			return false;
		for (MultiItem it : multi)
			if (it.page == page && it.text.equals(text))
				return false;
		MultiItem it = new MultiItem();
		it.page = page;
		it.boxes = m.selectionBoxes.clone();
		it.text = text;
		multi.add(it);
		return true;
	}

	public List<MultiItem> getMulti() {
		return multi;
	}

	public int multiCount() {
		return multi.size();
	}

	public void clearMulti() {
		multi.clear();
	}

	/** Kept selections in the order they were made, one per line. */
	public String multiText() {
		StringBuilder sb = new StringBuilder();
		for (MultiItem it : multi) {
			if (sb.length() > 0) sb.append('\n');
			sb.append(it.text);
		}
		return sb.toString();
	}

	public void putTSModel(int pageNum, TextSelectionModel tsmodel) {
		tsModel.put(pageNum, tsmodel);
	}

	public void rmTSModel(int pageNum) {
		tsModel.remove(pageNum);
	}

	public void clearTSModel() {
		tsModel.clear();
	}

	public static class TextSelectionModel {
		public StructuredText sText;        // page text structure
		public Quad[]    selectionBoxes;    // selection result on source
		public PointF[]  textHandles = new PointF[2];       // handles point on source
		public PointF[]  boundries= new PointF[2];          // boundries point on source
		public int       dir;               // 0: none, 1: left, 2: right, 3: both
		public boolean   area;              // true for a rectangular (area) selection
		public String    areaText;          // text picked by the area selection
		public android.graphics.Rect[]    rectHandles = new android.graphics.Rect[2];      // handles rect on view
	}
}
