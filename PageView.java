package net.timelegend.chaka.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.Quad;

import android.content.Context;
import android.graphics.Bitmap.Config;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Collections;

// Make our ImageViews opaque to optimize redraw
class OpaqueImageView extends ImageView {

	public OpaqueImageView(Context context) {
		super(context);
	}

	@Override
	public boolean isOpaque() {
		return true;
	}
}

/**
 * One rendered PDF page: a low resolution full page image, an optional
 * high resolution patch while zoomed, and an overlay that draws the text selection.
 */
public class PageView extends ViewGroup {
	protected final Context mContext;
	private final MuPDFCore mCore;
	private static final int PROGRESS_DIALOG_DELAY = 200;

	protected     int       mPageNumber;
	private       Point     mParentSize;
	protected     Point     mSize;   // Size of page at minimum zoom
	protected     float     mSourceScale;

	private       ImageView mEntire; // Image rendered at minimum zoom
	private       Bitmap    mEntireBm;
	private       Matrix    mEntireMat;
	private       CancellableAsyncTask<Void, Boolean> mDrawEntire;

	private       Point     mPatchViewSize; // View size on the basis of which the patch was created
	private       Rect      mPatchArea;
	private       ImageView mPatch;
	private       Bitmap    mPatchBm;
	private       CancellableAsyncTask<Void, Boolean> mDrawPatch;
	private       View      mSelectView;   // overlay drawing the selection
	private       boolean   mIsBlank;

	private final Paint    mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	private       ImageView mErrorIndicator;

	private       ProgressBar mBusyIndicator;
	private final Handler   mHandler = new Handler(Looper.getMainLooper());

	public PageView(Context c, MuPDFCore core, Point parentSize) {
		super(c);
		mContext = c;
		mCore = core;
		mParentSize = parentSize;
		setBackgroundColor(Color.WHITE);
		// the parent is correct screen
		mEntireBm = Bitmap.createBitmap(parentSize.x, parentSize.y, Config.ARGB_8888);
		mPatchBm = Bitmap.createBitmap(parentSize.x, parentSize.y, Config.ARGB_8888);
		mEntireMat = new Matrix();
	}

	private void reinit() {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel();
			mDrawEntire = null;
		}

		if (mDrawPatch != null) {
			mDrawPatch.cancel();
			mDrawPatch = null;
		}

		mIsBlank = true;
		mPageNumber = 0;

		if (mSize == null)
			mSize = mParentSize;

		if (mEntire != null) {
			mEntire.setImageBitmap(null);
			mEntire.invalidate();
		}

		if (mPatch != null) {
			mPatch.setImageBitmap(null);
			mPatch.invalidate();
		}

		mPatchViewSize = null;
		mPatchArea = null;

		clearRenderError();
	}

	public void releaseResources() {
		reinit();

		if (mBusyIndicator != null) {
			removeView(mBusyIndicator);
			mBusyIndicator = null;
		}
		clearRenderError();
	}

	public synchronized void releaseBitmaps() {
		reinit();

		// recycle bitmaps before releasing them.

		if (mEntireBm!=null)
			mEntireBm.recycle();
		mEntireBm = null;

		if (mPatchBm!=null)
			mPatchBm.recycle();
		mPatchBm = null;
	}

	public void blank(int page) {
		reinit();
		mPageNumber = page;

		if (mBusyIndicator == null) {
			mBusyIndicator = new ProgressBar(mContext);
			mBusyIndicator.setIndeterminate(true);
			addView(mBusyIndicator);
		}

		setBackgroundColor(Color.WHITE);
	}

	protected void clearRenderError() {
		if (mErrorIndicator == null)
			return;

		removeView(mErrorIndicator);
		mErrorIndicator = null;
		invalidate();
	}

	protected void setRenderError(String why) {

		int page = mPageNumber;
		reinit();
		mPageNumber = page;

		if (mBusyIndicator != null) {
			removeView(mBusyIndicator);
			mBusyIndicator = null;
		}
		if (mSelectView != null) {
			removeView(mSelectView);
			mSelectView = null;
		}

		if (mErrorIndicator == null) {
			mErrorIndicator = new OpaqueImageView(mContext);
			mErrorIndicator.setScaleType(ImageView.ScaleType.CENTER);
			addView(mErrorIndicator);
			Drawable mErrorIcon = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_error_red_24dp, null);
			mErrorIndicator.setImageDrawable(mErrorIcon);
			mErrorIndicator.setBackgroundColor(Color.WHITE);
		}

		setBackgroundColor(Color.TRANSPARENT);
		mErrorIndicator.bringToFront();
		mErrorIndicator.invalidate();
	}

	// size: page size in PDF units (x = width, y = height)
	public void setPage(int page, PointF size) {
		if (size == null) {
			setRenderError("Error loading page");
			size = new PointF(612, 792);
		}

		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel();
			mDrawEntire = null;
		}

		mIsBlank = false;
		// Selection may be missing because mIsBlank was true on last draw
		if (mSelectView != null)
			mSelectView.invalidate();

		mPageNumber = page;

		// Calculate scaled size that fits within the screen limits
		// This is the size at minimum zoom
		mSourceScale = Math.min(mParentSize.x/size.x, mParentSize.y/size.y);
		mSize = new Point((int)(size.x*mSourceScale), (int)(size.y*mSourceScale));

		if (mErrorIndicator != null)
			return;

		if (mEntire == null) {
			mEntire = new OpaqueImageView(mContext);
			mEntire.setScaleType(ImageView.ScaleType.MATRIX);
			addView(mEntire);
		}

		mEntire.setImageBitmap(null);
		mEntire.invalidate();

		// Render the page in the background
		mDrawEntire = new CancellableAsyncTask<Void, Boolean>(getDrawPageTask(mEntireBm, mSize.x, mSize.y, 0, 0, mSize.x, mSize.y)) {

			@Override
			public void onPreExecute() {
				setBackgroundColor(Color.WHITE);
				mEntire.setImageBitmap(null);
				mEntire.invalidate();

				if (mBusyIndicator == null) {
					mBusyIndicator = new ProgressBar(mContext);
					mBusyIndicator.setIndeterminate(true);
					addView(mBusyIndicator);
					mBusyIndicator.setVisibility(INVISIBLE);
					mHandler.postDelayed(new Runnable() {
						public void run() {
							if (mBusyIndicator != null)
								mBusyIndicator.setVisibility(VISIBLE);
						}
					}, PROGRESS_DIALOG_DELAY);
				}
			}

			@Override
			public void onPostExecute(Boolean result) {
				removeView(mBusyIndicator);
				mBusyIndicator = null;
				if (result.booleanValue()) {
					clearRenderError();
					mEntire.setImageBitmap(mEntireBm);
					mEntire.invalidate();
				} else {
					setRenderError("Error rendering page");
				}
				setBackgroundColor(Color.TRANSPARENT);
			}
		};

		mDrawEntire.execute();

		if (mSelectView == null) {
			mSelectView = new View(mContext) {
				@Override
				protected void onDraw(final Canvas canvas) {
					super.onDraw(canvas);

					final Paint paint = new Paint();

					// selections kept with "Select multiple"
					if (!mIsBlank) {
						paint.setColor(Tool.SELECTION_COLOR);
						for (MuPDFCore.MultiItem it : mCore.getMulti()) {
							if (it.page != mPageNumber) continue;
							for (Quad q : it.boxes)
								drawRect(q.toRect(), canvas, paint);
						}
					}

					MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber);
					if (!mIsBlank && tsmodel != null && tsmodel.selectionBoxes != null && tsmodel.selectionBoxes.length > 0) {
						paint.setColor(Tool.SELECTION_COLOR);
						for (Quad q : tsmodel.selectionBoxes) {
							drawRect(q.toRect(), canvas, paint);
						}
						Rect l = null, r = null;
						if ((tsmodel.dir & 1) > 0) {
							l = drawLeftHandle(tsmodel.textHandles[0].x, tsmodel.textHandles[0].y, canvas);
						}
						if ((tsmodel.dir & 2) > 0) {
							r = drawRightHandle(tsmodel.textHandles[1].x, tsmodel.textHandles[1].y, canvas);
						}
						tsmodel.rectHandles[0] = l;
						tsmodel.rectHandles[1] = r;
						mCore.putTSModel(mPageNumber, tsmodel);
					}
				}
			};

			addView(mSelectView);
		}
		requestLayout();
	}

	// scale from source (PDF units) to this view
	private float scale() {
		return mSourceScale * (float)getWidth() / (float)mSize.x;
	}

	private void drawRect(com.artifex.mupdf.fitz.Rect r, Canvas canvas, Paint paint) {
		PointF p0 = src2View(r.x0, r.y0);
		PointF p1 = src2View(r.x1, r.y1);
		canvas.drawRect(p0.x, p0.y, p1.x, p1.y, paint);
	}

	// Teardrop selection handle, 176x88 box with its pointed corner on the
	// top/bottom side facing the text. (l,t) is the top-left of the box.
	private void drawHandle(Canvas canvas, int l, int t, boolean cornerRight, boolean cornerBottom) {
		float cx = l + 88, cy = t + 44;
		mHandlePaint.setColor(Tool.HANDLE_COLOR);
		canvas.drawCircle(cx, cy, 44, mHandlePaint);
		float rl = cornerRight ? cx : cx - 44;
		float rt = cornerBottom ? cy : cy - 44;
		canvas.drawRect(rl, rt, rl + 44, rt + 44, mHandlePaint);
	}

	private Rect drawLeftHandle(float x0, float y0, Canvas canvas) {
		PointF p = src2View(x0, y0);
		int x = Math.round(p.x), y = Math.round(p.y);
		int viewWidth = getWidth(), viewHeight = getHeight();
		int hx1 = 132, hx2= 44, hy = 88;
		Rect r;
		if (x - hx1 >= 0 && y + hy <= viewHeight) {
			drawHandle(canvas, x - hx1, y, true, false);
			r = new Rect(x - hy, y, x, y + hy);
		}
		else if (x - hx1 >= 0) {
			drawHandle(canvas, x - hx1, y - hy, true, true);
			r = new Rect(x - hy, y - hy, x, y);
		}
		else if (y + hy <= viewHeight) {
			drawHandle(canvas, x - hx2, y, false, false);
			r = new Rect(x, y, x + hy, y + hy);
		}
		else {
			drawHandle(canvas, x - hx2, y - hy, false, true);
			r = new Rect(x, y - hy, x + hy, y);
		}
		return r;
	}

	private Rect drawRightHandle(float x1, float y1, Canvas canvas) {
		PointF p = src2View(x1, y1);
		int x = Math.round(p.x), y = Math.round(p.y);
		int viewWidth = getWidth(), viewHeight = getHeight();
		int hx1 = 132, hx2= 44, hy = 88;
		Rect r;
		if (x + hx1 <= viewWidth && y + hy <= viewHeight) {
			drawHandle(canvas, x - hx2, y, false, false);
			r = new Rect(x, y, x + hy, y + hy);
		}
		else if (x + hx1 <= viewWidth) {
			drawHandle(canvas, x - hx2, y - hy, false, true);
			r = new Rect(x, y - hy, x + hy, y);
		}
		else if (y + hy <= viewHeight) {
			drawHandle(canvas, x - hx1, y, true, false);
			r = new Rect(x - hy, y, x, y + hy);
		}
		else {
			drawHandle(canvas, x - hx1, y - hy, true, true);
			r = new Rect(x - hy, y - hy, x, y);
		}
		return r;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int x, y;
		switch(View.MeasureSpec.getMode(widthMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			x = mSize.x;
			break;
		default:
			x = View.MeasureSpec.getSize(widthMeasureSpec);
		}
		switch(View.MeasureSpec.getMode(heightMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			y = mSize.y;
			break;
		default:
			y = View.MeasureSpec.getSize(heightMeasureSpec);
		}

		setMeasuredDimension(x, y);

		if (mBusyIndicator != null) {
			int limit = Math.min(mParentSize.x, mParentSize.y)/2;
			mBusyIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
		}
		if (mErrorIndicator != null) {
			int limit = Math.min(mParentSize.x, mParentSize.y)/2;
			mErrorIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int w = right-left;
		int h = bottom-top;

		if (mEntire != null) {
			if (mEntire.getWidth() != w || mEntire.getHeight() != h) {
				mEntireMat.setScale(w/(float)mSize.x, h/(float)mSize.y);
				mEntire.setImageMatrix(mEntireMat);
				mEntire.invalidate();
			}
			mEntire.layout(0, 0, w, h);
		}

		if (mSelectView != null) {
			mSelectView.layout(0, 0, w, h);
		}

		if (mPatchViewSize != null) {
			if (mPatchViewSize.x != w || mPatchViewSize.y != h) {
				// Zoomed since patch was created
				mPatchViewSize = null;
				mPatchArea = null;
				if (mPatch != null) {
					mPatch.setImageBitmap(null);
					mPatch.invalidate();
				}
			} else {
				mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
			}
		}

		if (mBusyIndicator != null) {
			int bw = mBusyIndicator.getMeasuredWidth();
			int bh = mBusyIndicator.getMeasuredHeight();

			mBusyIndicator.layout((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
		}

		if (mErrorIndicator != null) {
			int bw = (int) (8.5 * mErrorIndicator.getMeasuredWidth());
			int bh = (int) (11 * mErrorIndicator.getMeasuredHeight());
			mErrorIndicator.layout((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
		}
	}

	// render a high resolution patch of the visible part while zoomed
	public void updateHq(boolean update) {
		if (mErrorIndicator != null) {
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatch.invalidate();
			}
			return;
		}

		Rect viewArea = new Rect(getLeft(),getTop(),getRight(),getBottom());
		boolean noPatch = viewArea.width() == mSize.x || viewArea.height() == mSize.y
				|| (viewArea.width() <= mSize.x && viewArea.height() <= mSize.y);   // zoomed out: base image is enough
		if (noPatch) {
			// If the viewArea's size matches the unzoomed size, there is no need for an hq patch
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatch.invalidate();
			}
		} else {
			final Point patchViewSize = new Point(viewArea.width(), viewArea.height());
			final Rect patchArea = new Rect(0, 0, mParentSize.x, mParentSize.y);

			// Intersect and test that there is an intersection
			if (!patchArea.intersect(viewArea))
				return;

			// Offset patch area to be relative to the view top left
			patchArea.offset(-viewArea.left, -viewArea.top);

			boolean area_unchanged = patchArea.equals(mPatchArea) && patchViewSize.equals(mPatchViewSize);

			// If being asked for the same area as last time and not because of an update then nothing to do
			if (area_unchanged && !update)
				return;

			// Stop the drawing of previous patch if still going
			if (mDrawPatch != null) {
				mDrawPatch.cancel();
				mDrawPatch = null;
			}

			// Create and add the image view if not already done
			if (mPatch == null) {
				mPatch = new OpaqueImageView(mContext);
				mPatch.setScaleType(ImageView.ScaleType.MATRIX);
				addView(mPatch);
				if (mSelectView != null)
					mSelectView.bringToFront();
			}

			CancellableTaskDefinition<Void, Boolean> task =
					getDrawPageTask(mPatchBm, patchViewSize.x, patchViewSize.y,
							patchArea.left, patchArea.top,
							patchArea.width(), patchArea.height());

			mDrawPatch = new CancellableAsyncTask<Void, Boolean>(task) {

				public void onPreExecute() {
					// prevent patch view splash
					mPatch.setImageBitmap(null);
					mPatch.invalidate();
				}

				public void onPostExecute(Boolean result) {
					if (result.booleanValue()) {
						mPatchViewSize = patchViewSize;
						mPatchArea = patchArea;
						clearRenderError();
						mPatch.setImageBitmap(mPatchBm);
						mPatch.invalidate();
						// Calling requestLayout here doesn't lead to a later call to layout. No idea
						// why, but apparently others have run into the problem.
						mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
					} else {
						setRenderError("Error rendering patch");
					}
				}
			};

			mDrawPatch.execute();
		}
	}

	public void removeHq() {
		// Stop the drawing of the patch if still going
		if (mDrawPatch != null) {
			mDrawPatch.cancel();
			mDrawPatch = null;
		}

		// And get rid of it
		mPatchViewSize = null;
		mPatchArea = null;
		if (mPatch != null) {
			mPatch.setImageBitmap(null);
			mPatch.invalidate();
		}
	}

	public int getPage() {
		return mPageNumber;
	}

	@Override
	public boolean isOpaque() {
		return true;
	}

	/* ---------------- text selection ---------------- */

	public boolean beginSelect(float x, float y) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber, true);
		if (tsmodel.sText == null) return false;

		PointF p = view2Src(x, y);
		com.artifex.mupdf.fitz.Point fp1 = new com.artifex.mupdf.fitz.Point(p.x, p.y);
		com.artifex.mupdf.fitz.Point fp2 = new com.artifex.mupdf.fitz.Point(p.x, p.y);
		Quad q = tsmodel.sText.snapSelection(fp1, fp2, 1);     // 0:char, 1:word, 2:line

		PointF ur = new PointF(q.ur_x, q.ur_y);
		PointF ll = new PointF(q.ll_x, q.ll_y);
		PointF lr = new PointF(q.lr_x, q.lr_y);
		PointF ml = new PointF(q.ul_x, (q.ul_y + q.ll_y) / 2);
		PointF mr = new PointF(q.lr_x, (q.lr_y + q.ur_y) / 2);
		boolean sel = (!ur.equals(lr)) && q.contains(p.x, p.y);
		if (sel) {
			tsmodel.area = false;
			tsmodel.areaText = null;
			tsmodel.selectionBoxes = new Quad[1];
			tsmodel.selectionBoxes[0] = q;
			tsmodel.textHandles[0] = ll;
			tsmodel.textHandles[1]= lr;
			tsmodel.boundries[0]= ml;
			tsmodel.boundries[1]= mr;
			tsmodel.dir = 3;
			mCore.putTSModel(mPageNumber, tsmodel);
			if (mSelectView != null)
				mSelectView.invalidate();
		}
		return sel;
	}

	public void selectAll(int dir) {
		selectAll(dir, 0);
	}

	/**
	*  dir: 1: left handle, 2: right handle, 3: both handle, 0: no handle
	*  end: when dir = 1/2, 1: handle extend to top left/right bottom
	*/
	public void selectAll(int dir, int end) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber, true);
		PointF p1, p2;

		if (dir == Tool.LEFT_HANDLE) {
			if (end == Tool.HANDLE_EXTEND) {
				p1 = view2Src(getLeft() + 1, getTop() + 1);
			}
			else {
				p1 = tsmodel.boundries[0];
			}
			p2 = view2Src(getRight() - 1, getBottom() - 1);
		}
		else if (dir == Tool.RIGHT_HANDLE) {
			if (end == Tool.HANDLE_EXTEND) {
				p2 = view2Src(getRight() - 1, getBottom() - 1);
			}
			else {
				p2 = tsmodel.boundries[1];
			}
			p1 = view2Src(getLeft() + 1, getTop() + 1);
		}
		else if (dir == Tool.BOTH_HANDLE || dir == Tool.NO_HANDLE) {
			p1 = view2Src(getLeft() + 1, getTop() + 1);
			p2 = view2Src(getRight() - 1, getBottom() - 1);
		}
		else
			return;

		tsmodel.rectHandles[0] = null;
		tsmodel.rectHandles[1] = null;
		onSelect(dir, p1, p2);
	}

	public void moveSelect(int dir, Float x, Float y) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber, true);
		PointF p1, p2;

		if (dir == 1) {                 // left handle
			if (x != null)
				p1 = view2Src(x, y);
			else
				p1 = tsmodel.boundries[0];
			p2 = view2Src(getRight() - 1, getBottom() - 1);
		}
		else if (dir == 2) {            // right handle
			p1 = view2Src(getLeft() + 1, getTop() + 1);

			if (x != null)
				p2 = view2Src(x, y);
			else
				p2 = tsmodel.boundries[1];
		}
		else if (dir == 0) {            // no handle
			p1 = view2Src(getLeft() + 1, getTop() + 1);
			p2 = view2Src(getRight() - 1, getBottom() - 1);
		}
		else {                          // both handle
			p1 = view2Src(x, y);
			p2 = tsmodel.boundries[dir - 3];

			if (p1.y > p2.y) {
				PointF pt = p1;
				p1 = p2;
				p2 = pt;
			}
			dir = 3;
		}
		onSelect(dir, p1, p2);
	}

	private void onSelect(int dir, PointF p1, PointF p2) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber, true);
		com.artifex.mupdf.fitz.Point fp1 = new com.artifex.mupdf.fitz.Point(p1.x, p1.y);
		com.artifex.mupdf.fitz.Point fp2 = new com.artifex.mupdf.fitz.Point(p2.x, p2.y);
		Quad[] qs = tsmodel.sText.highlight(fp1, fp2);
		if (
			(qs.length == 0)
			|| (p1.y < qs[0].ul_y && (qs[0].ul_y - p1.y < qs[0].ll_y - qs[0].ul_y))
			|| (p2.y > qs[qs.length - 1].lr_y && (p2.y - qs[qs.length - 1].lr_y < qs[qs.length - 1].lr_y - qs[qs.length - 1].ur_y))
		)
			return;

		PointF ll = new PointF(qs[0].ll_x, qs[0].ll_y);
		PointF lr = new PointF(qs[qs.length - 1].lr_x, qs[qs.length - 1].lr_y);
		PointF ml = new PointF(qs[0].ul_x, (qs[0].ul_y + qs[0].ll_y) / 2);
		PointF mr = new PointF(qs[qs.length - 1].lr_x, (qs[qs.length - 1].lr_y + qs[qs.length - 1].ur_y) / 2);
		tsmodel.area = false;
		tsmodel.areaText = null;
		tsmodel.selectionBoxes = qs;
		tsmodel.textHandles[0] = ll;
		tsmodel.textHandles[1]= lr;
		tsmodel.boundries[0]= ml;
		tsmodel.boundries[1]= mr;
		tsmodel.dir = dir;
		mCore.putTSModel(mPageNumber, tsmodel);
		if (mSelectView != null)
			mSelectView.invalidate();
	}

	/* ---------------- area (rectangle) selection ---------------- */

	private static class Seg {
		float cy, x, h;
		String text;
	}

	/**
	 * Select every piece of text inside the rectangle (given in the parent's
	 * coordinates), regardless of reading order. Each text line that crosses the
	 * box contributes only the characters lying inside the box.
	 * Returns false if the box contains no text on this page.
	 */
	public boolean selectArea(float x0, float y0, float x1, float y1) {
		MuPDFCore.TextSelectionModel tsm = mCore.getTSModel(mPageNumber, true);
		if (tsm == null || tsm.sText == null) return false;

		PointF a = view2Src(Math.min(x0, x1), Math.min(y0, y1));
		PointF b = view2Src(Math.max(x0, x1), Math.max(y0, y1));
		float rx0 = a.x, ry0 = a.y, rx1 = b.x, ry1 = b.y;

		// every text line of the page, as one quad per line
		PointF tl = view2Src(getLeft() + 1, getTop() + 1);
		PointF br = view2Src(getRight() - 1, getBottom() - 1);
		Quad[] lines;
		try {
			lines = tsm.sText.highlight(new com.artifex.mupdf.fitz.Point(tl.x, tl.y),
					new com.artifex.mupdf.fitz.Point(br.x, br.y));
		} catch (Throwable t) {
			lines = null;
		}
		if (lines == null || lines.length == 0) return false;

		ArrayList<Quad> boxes = new ArrayList<>();
		ArrayList<Seg> segs = new ArrayList<>();

		for (Quad lq : lines) {
			float lx0 = Math.min(Math.min(lq.ul_x, lq.ur_x), Math.min(lq.ll_x, lq.lr_x));
			float lx1 = Math.max(Math.max(lq.ul_x, lq.ur_x), Math.max(lq.ll_x, lq.lr_x));
			float ly0 = Math.min(Math.min(lq.ul_y, lq.ur_y), Math.min(lq.ll_y, lq.lr_y));
			float ly1 = Math.max(Math.max(lq.ul_y, lq.ur_y), Math.max(lq.ll_y, lq.lr_y));
			float lh = ly1 - ly0;
			if (lh <= 0) continue;

			// the box must cover a fair part of the line's height and overlap it sideways
			float oy = Math.min(ly1, ry1) - Math.max(ly0, ry0);
			float ox = Math.min(lx1, rx1) - Math.max(lx0, rx0);
			if (oy < lh * 0.3f || ox <= 0) continue;

			// the line's centre axis, from its left end A to its right end B
			float ax = (lq.ul_x + lq.ll_x) / 2, ay = (lq.ul_y + lq.ll_y) / 2;
			float bx = (lq.ur_x + lq.lr_x) / 2, by = (lq.ur_y + lq.lr_y) / 2;
			float dx = bx - ax;

			float t0 = 0, t1 = 1;
			if (Math.abs(dx) > 0.001f) {
				float ta = (rx0 - ax) / dx, tb = (rx1 - ax) / dx;
				t0 = Math.max(0f, Math.min(ta, tb));
				t1 = Math.min(1f, Math.max(ta, tb));
			}
			if (t1 <= t0 || (t1 - t0) * Math.abs(dx) < 1f) continue;

			com.artifex.mupdf.fitz.Point pa = new com.artifex.mupdf.fitz.Point(ax + (bx - ax) * t0, ay + (by - ay) * t0);
			com.artifex.mupdf.fitz.Point pb = new com.artifex.mupdf.fitz.Point(ax + (bx - ax) * t1, ay + (by - ay) * t1);

			Quad[] part;
			String text;
			try {
				part = tsm.sText.highlight(pa, pb);
				text = tsm.sText.copy(pa, pb);
			} catch (Throwable t) {
				continue;
			}
			if (part == null || part.length == 0) continue;
			for (Quad q : part) boxes.add(q);

			if (text != null && text.trim().length() > 0) {
				Seg sg = new Seg();
				sg.cy = (ly0 + ly1) / 2;
				sg.x = Math.min(pa.x, pb.x);
				sg.h = lh;
				sg.text = text.trim();
				segs.add(sg);
			}
		}
		if (boxes.isEmpty()) return false;

		// build the text: rows top to bottom, items left to right within a row
		Collections.sort(segs, (p, q) -> Float.compare(p.cy, q.cy));
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < segs.size()) {
			Seg first = segs.get(i);
			ArrayList<Seg> row = new ArrayList<>();
			row.add(first);
			int j = i + 1;
			while (j < segs.size() && Math.abs(segs.get(j).cy - first.cy) < first.h * 0.5f) {
				row.add(segs.get(j));
				j++;
			}
			Collections.sort(row, (p, q) -> Float.compare(p.x, q.x));
			if (sb.length() > 0) sb.append('\n');
			for (int k = 0; k < row.size(); k++) {
				if (k > 0) sb.append(' ');
				sb.append(row.get(k).text);
			}
			i = j;
		}

		Quad firstBox = boxes.get(0);
		Quad lastBox = boxes.get(boxes.size() - 1);
		tsm.selectionBoxes = boxes.toArray(new Quad[0]);
		tsm.textHandles[0] = new PointF(firstBox.ll_x, firstBox.ll_y);
		tsm.textHandles[1] = new PointF(lastBox.lr_x, lastBox.lr_y);
		tsm.boundries[0] = new PointF(firstBox.ul_x, (firstBox.ul_y + firstBox.ll_y) / 2);
		tsm.boundries[1] = new PointF(lastBox.lr_x, (lastBox.lr_y + lastBox.ur_y) / 2);
		tsm.dir = 0;                 // no flow handles for an area selection
		tsm.area = true;
		tsm.areaText = sb.toString();
		mCore.putTSModel(mPageNumber, tsm);
		if (mSelectView != null)
			mSelectView.invalidate();
		return true;
	}

	/** Bounds (in the parent's coordinates) of this page's area selection, or null. */
	public Rect getAreaRect() {
		MuPDFCore.TextSelectionModel m = mCore.getTSModel(mPageNumber);
		if (m == null || !m.area || m.selectionBoxes == null || m.selectionBoxes.length == 0)
			return null;
		float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
		for (Quad q : m.selectionBoxes) {
			com.artifex.mupdf.fitz.Rect fr = q.toRect();
			PointF p0 = src2View2(fr.x0, fr.y0);
			PointF p1 = src2View2(fr.x1, fr.y1);
			l = Math.min(l, Math.min(p0.x, p1.x));
			t = Math.min(t, Math.min(p0.y, p1.y));
			r = Math.max(r, Math.max(p0.x, p1.x));
			b = Math.max(b, Math.max(p0.y, p1.y));
		}
		return new Rect((int) l, (int) t, (int) r, (int) b);
	}

	public String copy(int position) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(position);
		if (tsmodel == null) return "";
		if (tsmodel.area)
			return tsmodel.areaText != null ? tsmodel.areaText : "";
		PointF p1 = tsmodel.boundries[0];
		PointF p2 = tsmodel.boundries[1];
		com.artifex.mupdf.fitz.Point fp1 = new com.artifex.mupdf.fitz.Point(p1.x, p1.y);
		com.artifex.mupdf.fitz.Point fp2 = new com.artifex.mupdf.fitz.Point(p2.x, p2.y);
		return tsmodel.sText.copy(fp1, fp2);
	}

	public Rect getSelectionRect(int position) {
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(position);
		PointF p1 = src2View2(tsmodel.boundries[0].x, tsmodel.boundries[0].y - (tsmodel.textHandles[0].y - tsmodel.boundries[0].y) * 2);
		PointF p2 = src2View2(tsmodel.boundries[1].x, tsmodel.boundries[1].y + (tsmodel.textHandles[1].y - tsmodel.boundries[1].y) * 2);
		return new Rect((int)p1.x, (int)p1.y, (int)p2.x, (int)p2.y);
	}

	/** Redraw the selection overlay (used when kept selections change). */
	public void refreshSelection() {
		if (mSelectView != null)
			mSelectView.invalidate();
	}

	public void unSelect() {
		unSelect(mPageNumber);
		if (mSelectView != null)
			mSelectView.invalidate();
	}

	public void unSelect(int position) {
		mCore.rmTSModel(position);
	}

	public boolean isOnHandle(float x, float y, int ind) {
		float x1 = x - getLeft();
		float y1 = y - getTop();
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber);
		if (tsmodel == null) return false;
		Rect h = tsmodel.rectHandles[ind];

		if (h != null) {
			return h.contains((int)x1, (int)y1);
		}
		return false;
	}

	public boolean isOnBox(float x, float y) {
		PointF p = view2Src(x, y);
		MuPDFCore.TextSelectionModel tsmodel = mCore.getTSModel(mPageNumber);
		if (tsmodel == null || tsmodel.selectionBoxes == null) return false;
		for (Quad q : tsmodel.selectionBoxes) {
			if (q.contains(p.x, p.y))
				return true;
		}
		return false;
	}

	/* ---------------- rendering tasks ---------------- */

	protected CancellableTaskDefinition<Void, Boolean> getDrawPageTask(final Bitmap bm, final int sizeX, final int sizeY,
			final int patchX, final int patchY, final int patchWidth, final int patchHeight) {
		return new MuPDFCancellableTaskDefinition<Void, Boolean>() {
			@Override
			public Boolean doInBackground(Cookie cookie, Void ... params) {
				if (bm == null)
					return false;
				try {
					mCore.drawPage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie);
					return true;
				} catch (RuntimeException e) {
					return false;
				}
			}
		};

	}

	/* ---------------- coordinate conversion ---------------- */

	// view (parent) coordinates -> source (PDF units)
	public PointF view2Src(float x, float y) {
		float s = scale();
		return new PointF((x - getLeft()) / s, (y - getTop()) / s);
	}

	// source -> this view's own coordinates
	public PointF src2View(float x, float y) {
		float s = scale();
		return new PointF(x * s, y * s);
	}

	// source -> parent coordinates
	public PointF src2View2(float x, float y) {
		float s = scale();
		return new PointF(x * s + getLeft(), y * s + getTop());
	}
}
