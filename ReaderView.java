package net.timelegend.chaka.viewer;

import java.util.LinkedList;
import java.util.NoSuchElementException;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.Magnifier;
import android.widget.Scroller;

import androidx.activity.OnBackPressedCallback;

/**
 * Continuous vertical PDF page scroller with pinch zoom and text selection.
 */
public class ReaderView
		extends AdapterView<Adapter>
		implements GestureDetector.OnGestureListener, ScaleGestureDetector.OnScaleGestureListener, Runnable {
	private boolean tapDisabled = false;
	private int tapPageMargin;

	private static final int MOVING_DIAGONALLY = 0;
	private static final int MOVING_LEFT       = 1;
	private static final int MOVING_RIGHT      = 2;
	private static final int MOVING_UP         = 3;
	private static final int MOVING_DOWN       = 4;

	private static final int FLING_MARGIN      = 100;
	private static final int GAP               = 12;   // visible gap between pages on the dark background

	private static final float MIN_SCALE        = 0.35f;   // below 1 = zoomed out past the screen width
	private static final float MAX_SCALE        = 64.0f;

	private static final int MOVE_DELAY         = 100;

	public enum SELECT{
		NO_SELECT,
		SELECTING,
		MOVE_LEFT,
		MOVE_RIGHT,
		MOVE_IN,
		MOVE_NONE
	}

	private PageAdapter           mAdapter;
	protected int               mCurrent;    // Adapter's index for the current view
	protected boolean           mResetLayout;
	private final SparseArray<View>
				  mChildViews = new SparseArray<View>(7);
					       // Shadows the children of the adapter view
					       // but with more sensible indexing
	private final LinkedList<View>
				  mViewCache = new LinkedList<View>();
	private boolean           mUserInteracting;  // Whether the user is interacting
	private boolean           mScaling;    // Whether the user is currently pinch zooming
	protected float             mScale     = 1.0f;
	private int               mXScroll;    // Scroll amounts recorded from events.
	private int               mYScroll;    // and then accounted for in onLayout
	private GestureDetector mGestureDetector;
	private ScaleGestureDetector mScaleGestureDetector;
	private Scroller    mScroller;
	private Stepper     mStepper;
	private int               mScrollerLastX;
	private int               mScrollerLastY;
	private float		  mLastScaleFocusX;
	private float		  mLastScaleFocusY;
	protected int           mPrevLeft;
	protected int           mPrevTop;
	private SELECT        mSelecting = SELECT.NO_SELECT;    // text select state
	private int           mSelectLeftView;                  // view pageNumber with text select left handle
	private int           mSelectRightView;                 // view pageNumber with text select right handle
	private ActionMode      mTextActionMode;
	private ActionMode.Callback actionModeCallback;
	private boolean         mActionModeVisible;
	private Magnifier       mMagnifier;
	private boolean         mBeginSelect = false;
	// enabled only while a selection exists, so Back clears the selection first
	private final OnBackPressedCallback mBackCallback = new OnBackPressedCallback(false) {
		@Override
		public void handleOnBackPressed() {
			if (mSelecting != SELECT.NO_SELECT)
				endSelect();
			else if (hasMulti())
				clearMulti();
			else if (mAreaMode)
				setAreaMode(false);
		}
	};
	private boolean       mLongPress = false;               // as longpress
	private MotionEvent   mLongPressEvent;
	private Runnable      mLongPressRunnable;
	private long          mMoveTime = 0L;

	/** Notified (on the UI thread) whenever the current page changes. */
	public interface PageListener {
		void onPageChanged(int page, int pageCount);
	}
	private PageListener  mPageListener;
	private int           mReportedPage = -1;

	/** Tells the host when the area selection tool is switched on or off. */
	public interface AreaModeListener {
		void onAreaModeChanged(boolean active);
	}
	private AreaModeListener mAreaListener;
	private boolean       mAreaMode = false;       // area selection tool active
	private boolean       mAreaDrawing = false;    // a box is being dragged
	private boolean       mAreaSelected = false;   // an area selection is showing
	private float         mAreaX0, mAreaY0, mAreaX1, mAreaY1;
	/** Colour of the area around and between pages (dark grey; use 0xFF000000 for pure black). */
	private static final int BACKGROUND_COLOR = 0xFF2E2E2E;
	public static final int MENU_SELECT_MULTIPLE = 0x5E1;

	/** Tells the host how many selections are being kept by "Select multiple". */
	public interface MultiListener {
		void onMultiSelectChanged(int count);
	}
	private MultiListener mMultiListener;
	private final Paint   mAreaFill = new Paint();
	private final Paint   mAreaStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

	public ReaderView(Context context) {
		super(context);
		setup(context);
	}

	public ReaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setup(context);
	}

	public ReaderView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		setup(context);
	}

	private void setup(Context context)
	{
		mGestureDetector = new GestureDetector(context, this);
		mGestureDetector.setIsLongpressEnabled(false);
		// double tap switches the area selection tool on / off
		mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
			public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
			public boolean onDoubleTap(MotionEvent e) {
				setAreaMode(!mAreaMode);
				return true;
			}
			public boolean onDoubleTapEvent(MotionEvent e) { return false; }
		});
		setBackgroundColor(BACKGROUND_COLOR);
		float density = context.getResources().getDisplayMetrics().density;
		mAreaFill.setColor(0x302196F3);
		mAreaStroke.setColor(0xFF2196F3);
		mAreaStroke.setStyle(Paint.Style.STROKE);
		mAreaStroke.setStrokeWidth(2 * density);
		mScaleGestureDetector = new ScaleGestureDetector(context, this);
		mScroller = new Scroller(context);
		mStepper = new Stepper(this, this);
		actionModeCallback = new TextActionModeCallback(this);
		// android 10, api29
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			mMagnifier = new Magnifier.Builder(this)
					.setDefaultSourceToMagnifierOffset(0, -200)
					.build();
		}

		// Tap margins at top/bottom of the screen: about 1 inch, but
		// never less than 100 pixels and never more than 1/5 of the screen.
		DisplayMetrics dm = context.getResources().getDisplayMetrics();
		tapPageMargin = (int)dm.xdpi;
		if (tapPageMargin < 100)
			tapPageMargin = 100;
		if (tapPageMargin > dm.widthPixels/5)
			tapPageMargin = dm.widthPixels/5;
	}

	public OnBackPressedCallback getBackCallback() {
		return mBackCallback;
	}

	// When advancing down the page, we want to advance by about
	// 90% of a screenful. But we'd be happy to advance by between
	// 80% and 95% if it means we hit the bottom in a whole number
	// of steps.
	private int smartAdvanceAmount(int screenHeight, int max) {
		int advance = (int)(screenHeight * 0.9 + 0.5);
		int leftOver = max % advance;
		int steps = max / advance;
		if (leftOver == 0) {
			// We'll make it exactly. No adjustment
		} else if ((float)leftOver / steps <= screenHeight * 0.05) {
			// We can adjust up by less than 5% to make it exact.
			advance += (int)((float)leftOver/steps + 0.5);
		} else {
			int overshoot = advance - leftOver;
			if ((float)overshoot / steps <= screenHeight * 0.1) {
				// We can adjust down by less than 10% to make it exact.
				advance -= (int)((float)overshoot/steps + 0.5);
			}
		}
		if (advance > max)
			advance = max;
		return advance;
	}

	public void smartMoveForwards() {
		View v = mChildViews.get(mCurrent);
		if (v == null)
			return;

		// screenWidth/Height are the actual width/height of the screen. e.g. 480/800
		int screenWidth = getWidth();
		int screenHeight = getHeight();
		// We might be mid scroll; we want to calculate where we scroll to based on
		// where this scroll would end, not where we are now (to allow for people
		// bashing 'forwards' very fast.
		int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
		int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
		// right/bottom is in terms of pixels within the scaled document; e.g. 1000
		int top = -(v.getTop() + mYScroll + remainingY);
		int right = screenWidth -(v.getLeft() + mXScroll + remainingX);
		int bottom = screenHeight+top;
		// docWidth/Height are the width/height of the scaled document e.g. 2000x3000
		int docWidth = v.getMeasuredWidth();
		int docHeight = v.getMeasuredHeight();

		int xOffset, yOffset;
		if (bottom >= docHeight) {
			// We are flush with the bottom. Advance to next column.
			if (right + screenWidth > docWidth) {
				// No room for another column - go to next page
				View nv = mChildViews.get(mCurrent+1);
				if (nv == null) // No page to advance to
					return;
				int nextTop = -(nv.getTop() + mYScroll + remainingY);
				int nextLeft = -(nv.getLeft() + mXScroll + remainingX);
				int nextDocWidth = nv.getMeasuredWidth();
				int nextDocHeight = nv.getMeasuredHeight();

				// Allow for the next page maybe being shorter than the screen is high
				yOffset = (nextDocHeight < screenHeight ? ((nextDocHeight - screenHeight)>>1) : 0);

				if (nextDocWidth < screenWidth) {
					// Next page is too narrow to fill the screen. Scroll to the top, centred.
					xOffset = (nextDocWidth - screenWidth)>>1;
				} else {
					// Reset X back to the left hand column
					xOffset = right % screenWidth;
					// Adjust in case the previous page is less wide
					if (xOffset + screenWidth > nextDocWidth)
						xOffset = nextDocWidth - screenWidth;
				}
				xOffset -= nextLeft;
				yOffset -= nextTop;
			} else {
				// Move to top of next column
				xOffset = screenWidth;
				yOffset = screenHeight - bottom;
			}
		} else {
			// Advance by 90% of the screen height downwards (in case lines are partially cut off)
			xOffset = 0;
			yOffset = smartAdvanceAmount(screenHeight, docHeight - bottom);
		}
		mScrollerLastX = mScrollerLastY = 0;
		mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400);
		mStepper.prod();
	}

	public void smartMoveBackwards() {
		View v = mChildViews.get(mCurrent);
		if (v == null)
			return;

		int screenWidth = getWidth();
		int screenHeight = getHeight();
		// We might be mid scroll; we want to calculate where we scroll to based on
		// where this scroll would end, not where we are now (to allow for people
		// bashing 'forwards' very fast.
		int remainingX = mScroller.getFinalX() - mScroller.getCurrX();
		int remainingY = mScroller.getFinalY() - mScroller.getCurrY();
		// left/top is in terms of pixels within the scaled document; e.g. 1000
		int left = -(v.getLeft() + mXScroll + remainingX);
		int top = -(v.getTop() + mYScroll + remainingY);
		// docWidth/Height are the width/height of the scaled document e.g. 2000x3000
		int docHeight = v.getMeasuredHeight();

		int xOffset, yOffset;
		if (top <= 0) {
			// We are flush with the top. Step back to previous column.
			if (left < screenWidth) {
				/* No room for previous column - go to previous page */
				View pv = mChildViews.get(mCurrent-1);
				if (pv == null) /* No page to advance to */
					return;
				int prevDocWidth = pv.getMeasuredWidth();
				int prevDocHeight = pv.getMeasuredHeight();

				// Allow for the next page maybe being shorter than the screen is high
				yOffset = (prevDocHeight < screenHeight ? ((prevDocHeight - screenHeight)>>1) : 0);

				int prevLeft = -(pv.getLeft() + mXScroll);
				int prevTop = -(pv.getTop() + mYScroll);
				if (prevDocWidth < screenWidth) {
					// Previous page is too narrow to fill the screen. Scroll to the bottom, centred.
					xOffset = (prevDocWidth - screenWidth)>>1;
				} else {
					// Reset X back to the right hand column
					xOffset = (left > 0 ? left % screenWidth : 0);
					if (xOffset + screenWidth > prevDocWidth)
						xOffset = prevDocWidth - screenWidth;
					while (xOffset + screenWidth*2 < prevDocWidth)
						xOffset += screenWidth;
				}
				xOffset -= prevLeft;
				yOffset -= prevTop;
			} else {
				// Move to bottom of previous column
				xOffset = -screenWidth;
				yOffset = docHeight - screenHeight + top;
			}
		} else {
			// Retreat by 90% of the screen height downwards (in case lines are partially cut off)
			xOffset = 0;
			yOffset = -smartAdvanceAmount(screenHeight, top);
		}
		mScrollerLastX = mScrollerLastY = 0;
		mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400);
		mStepper.prod();
	}

	/* Recreate all page views, keeping the current position (used on size change). */
	public void refresh() {
		endSelect();
		mResetLayout = true;
		savePosition();
		mXScroll = mYScroll = 0;
		mSelecting = SELECT.NO_SELECT;

		/* All page views need recreating since both page and screen has changed size,
		 * invalidating both sizes and bitmaps. */
		if (mAdapter != null)
			mAdapter.refresh();
		for (int i = 0; i < mChildViews.size(); i++) {
			View v = mChildViews.valueAt(i);
			onNotInUse(v);
			removeViewInLayout(v);
		}
		mChildViews.clear();
		mViewCache.clear();

		requestLayout();
	}

	public PageView getDisplayedView() {
		return (PageView) mChildViews.get(mCurrent);
	}

	/** 0-based index of the page currently shown. */
	public int getCurrentPage() {
		return mCurrent;
	}

	public void setPageListener(PageListener l) {
		mPageListener = l;
		mReportedPage = -1;
		requestLayout();
	}

	/** Jump to a 0-based page (clamped), showing it from its top-left corner. */
	public void goToPage(int page) {
		if (mAdapter == null)
			return;
		int count = mAdapter.getCount();
		if (count <= 0)
			return;
		if (page < 0) page = 0;
		if (page >= count) page = count - 1;

		endSelect();
		mScroller.forceFinished(true);
		mXScroll = mYScroll = 0;
		mResetLayout = false;

		for (int i = 0; i < mChildViews.size(); i++) {
			View v = mChildViews.valueAt(i);
			onNotInUse(v);
			mViewCache.add(v);
			removeViewInLayout(v);
		}
		mChildViews.clear();

		mCurrent = page;
		requestLayout();
		mStepper.prod();
	}

	public void setMultiListener(MultiListener l) {
		mMultiListener = l;
	}

	private boolean hasMulti() {
		return mAdapter != null && mAdapter.getCore().multiCount() > 0;
	}

	/** Back is only intercepted while there is something to cancel. */
	private void updateBack() {
		mBackCallback.setEnabled(mAreaMode || hasMulti() || mSelecting != SELECT.NO_SELECT);
	}

	private void multiChanged() {
		for (int i = 0; i < mChildViews.size(); i++)
			((PageView) mChildViews.valueAt(i)).refreshSelection();
		updateBack();
		if (mMultiListener != null)
			mMultiListener.onMultiSelectChanged(mAdapter != null ? mAdapter.getCore().multiCount() : 0);
	}

	/** Keep the current selection highlighted and let the person select more. */
	public void selectMultiple() {
		if (mSelecting == SELECT.NO_SELECT || mAdapter == null)
			return;
		PageView pv = getDisplayedView();
		if (pv != null) {
			for (int i = mSelectLeftView; i <= mSelectRightView; i++)
				mAdapter.getCore().commitSelection(i, pv.copy(i));
		}
		endSelect();      // removes the active selection and its toolbar; kept ones stay drawn
		multiChanged();
	}

	/** Drop all kept selections. */
	public void clearMulti() {
		if (mAdapter == null)
			return;
		mAdapter.getCore().clearMulti();
		multiChanged();
	}

	/** End everything: the active selection and all kept ones. */
	public void finishAll() {
		clearMulti();
		endSelect();
	}

	public void setAreaModeListener(AreaModeListener l) {
		mAreaListener = l;
	}

	public boolean isAreaMode() {
		return mAreaMode;
	}

	public void setAreaMode(boolean on) {
		if (mAreaMode == on)
			return;
		endSelect();
		mAreaMode = on;
		mAreaDrawing = false;
		mLongPress = false;
		updateBack();
		if (mAreaListener != null)
			mAreaListener.onAreaModeChanged(on);
		invalidate();
	}

	private void handleAreaEvent(MotionEvent e) {
		switch (e.getActionMasked()) {
		case MotionEvent.ACTION_DOWN:
			if (mSelecting != SELECT.NO_SELECT)
				endSelect();
			mAreaX0 = mAreaX1 = e.getX();
			mAreaY0 = mAreaY1 = e.getY();
			mAreaDrawing = true;
			break;
		case MotionEvent.ACTION_MOVE:
			if (mAreaDrawing) {
				if (e.getPointerCount() == 1 && !mScaling) {
					mAreaX1 = e.getX();
					mAreaY1 = e.getY();
				} else {
					mAreaDrawing = false;   // a second finger means pinch / pan
				}
				invalidate();
			}
			break;
		case MotionEvent.ACTION_POINTER_DOWN:
			if (mAreaDrawing) {
				mAreaDrawing = false;
				invalidate();
			}
			break;
		case MotionEvent.ACTION_UP:
			if (mAreaDrawing) {
				mAreaDrawing = false;
				float slop = 2 * ViewConfiguration.get(getContext()).getScaledTouchSlop();
				if (Math.max(Math.abs(mAreaX1 - mAreaX0), Math.abs(mAreaY1 - mAreaY0)) > slop)
					applyAreaSelection();
				invalidate();
			}
			break;
		case MotionEvent.ACTION_CANCEL:
			mAreaDrawing = false;
			invalidate();
			break;
		}
	}

	/** Select the text inside the dragged box on every page it touches. */
	private void applyAreaSelection() {
		float l = Math.min(mAreaX0, mAreaX1), r = Math.max(mAreaX0, mAreaX1);
		float t = Math.min(mAreaY0, mAreaY1), b = Math.max(mAreaY0, mAreaY1);

		endSelect();
		int minKey = Integer.MAX_VALUE, maxKey = -1;
		for (int i = 0; i < mChildViews.size(); i++) {
			PageView pv = (PageView) mChildViews.valueAt(i);
			if (r < pv.getLeft() || l > pv.getRight() || b < pv.getTop() || t > pv.getBottom())
				continue;
			if (pv.selectArea(l, t, r, b)) {
				int key = mChildViews.keyAt(i);
				minKey = Math.min(minKey, key);
				maxKey = Math.max(maxKey, key);
			}
		}
		if (maxKey < 0) {
			Tool.toast(Tool.getResourceString(R.string.area_no_text));
			return;
		}

		mSelectLeftView = minKey;
		mSelectRightView = maxKey;
		mSelecting = SELECT.SELECTING;
		mAreaSelected = true;
		mBackCallback.setEnabled(true);
		mTextActionMode = startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING);
		mActionModeVisible = false;
		showFloatingToolbar();
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		super.dispatchDraw(canvas);
		if (mAreaDrawing) {
			float l = Math.min(mAreaX0, mAreaX1), r = Math.max(mAreaX0, mAreaX1);
			float t = Math.min(mAreaY0, mAreaY1), b = Math.max(mAreaY0, mAreaY1);
			canvas.drawRect(l, t, r, b, mAreaFill);
			canvas.drawRect(l, t, r, b, mAreaStroke);
		}
	}

	public void run() {
		if (!mScroller.isFinished()) {
			mScroller.computeScrollOffset();
			int x = mScroller.getCurrX();
			int y = mScroller.getCurrY();
			mXScroll += x - mScrollerLastX;
			mYScroll += y - mScrollerLastY;
			mScrollerLastX = x;
			mScrollerLastY = y;
			requestLayout();
			mStepper.prod();
		}
		else if (!mUserInteracting) {
			// End of an inertial scroll and the user is not interacting.
			// The layout is stable

			// best time to show floating toolbar
			updateFloatingToolbarInPage();
			View v = mChildViews.get(mCurrent);

			if (v != null) {
				if (mScroller.isFinished())
					postSettle(v);
			}
		}
	}

	public boolean onDown(MotionEvent e) {
		if (mLongPressRunnable == null) {
			mLongPressRunnable = new Runnable() {
				public void run() {
					if (mLongPress && mLongPressEvent != null) {
						mLongPress = false;
						beginSelect(mLongPressEvent.getX(), mLongPressEvent.getY());
					}
					mLongPressEvent = null;
				}
			};
		}
		if (mAreaMode) {
			// area mode: touches draw a box, no long-press word selection
		}
		else if (mSelecting == SELECT.NO_SELECT) {
			removeCallbacks(mLongPressRunnable);
			mLongPress = true;
			mLongPressEvent = e;
			postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
		}
		else if (mSelecting == SELECT.SELECTING) {
			inSelect(e.getX(), e.getY());
		}

		mScroller.forceFinished(true);
		return true;
	}

	private void beginSelect(float x, float y) {
		for (int i = 0; i < mChildViews.size(); i++) {
			PageView pv = (PageView) mChildViews.valueAt(i);
			if (pointInView(x, y, pv)) {
				if (pv.beginSelect(x, y)) {
					mSelecting = SELECT.SELECTING;
					mSelectLeftView = mSelectRightView = mChildViews.keyAt(i);
					mTextActionMode = startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING);
					mActionModeVisible = false;
					mBackCallback.setEnabled(true);
					mBeginSelect = true;
					return;
				}
			}
		}
	}

	private void inSelect(float x, float y) {
		for (int i = 0; i < mChildViews.size(); i++) {
			PageView pv = (PageView) mChildViews.valueAt(i);
			if (pointInView(x, y, pv)) {
				int key = mChildViews.keyAt(i);
				if (key == mSelectLeftView && pv.isOnHandle(x, y, 0)) {
					mSelecting = SELECT.MOVE_LEFT;
				}
				else if (key == mSelectRightView && pv.isOnHandle(x, y, 1)) {
					mSelecting = SELECT.MOVE_RIGHT;
				}
				else if (key >= mSelectLeftView && key <= mSelectRightView && pv.isOnBox(x, y)) {
					mSelecting = SELECT.MOVE_IN;
				}
				else
					mSelecting = SELECT.MOVE_NONE;
				break;
			}
		}
	}

	private void moveSelect(float x, float y) {
		long mt = System.currentTimeMillis();
		if (mt - mMoveTime < MOVE_DELAY) return;

		int v1 = (mSelecting == SELECT.MOVE_LEFT) ? mSelectRightView : mSelectLeftView;
		int v2 = -1;
		for (int i = 0; i < mChildViews.size(); i++) {
			PageView pv = (PageView) mChildViews.valueAt(i);
			if (pointInView(x, y, pv)) {
				v2 = mChildViews.keyAt(i);
				break;
			}
		}
		if (v2 == -1)
			return;

		mSelectLeftView = Math.min(v1, v2);
		mSelectRightView = Math.max(v1, v2);
		int ind = (v1 < v2) ? 1 : ((v1 > v2) ? -1 : 0);
		for (int i = 0; i < mChildViews.size(); i++) {
			PageView pv = (PageView) mChildViews.valueAt(i);
			int key = mChildViews.keyAt(i);
			if (ind == 1) {
				if (key == v1) {
					pv.moveSelect(1, null, null);
				}
				else if (key == v2) {
					pv.moveSelect(2, x, y);
				}
				else if (key > v1 && key < v2) {
					pv.moveSelect(0, null, null);
				}
				else {
					pv.unSelect();
				}
			}
			else if (ind == -1) {
				if (key == v2) {
					pv.moveSelect(1, x, y);
				}
				else if (key == v1) {
					pv.moveSelect(2, null, null);
				}
				else if (key > v2 && key < v1) {
					pv.moveSelect(0, null, null);
				}
				else {
					pv.unSelect();
				}
			}
			else {  // ind == 0
				if (key == v1) {
					int m = (mSelecting == SELECT.MOVE_RIGHT) ? 0 : 1;
					pv.moveSelect(3 + m, x, y);
				}
				else {
					pv.unSelect();
				}
			}
		}
		mMoveTime = System.currentTimeMillis();
	}

	public String getSelectedText() {
		StringBuffer buffer = new StringBuffer("");
		if (mAdapter != null)
			buffer.append(mAdapter.getCore().multiText());

		PageView pv = getDisplayedView();
		if (mSelecting != SELECT.NO_SELECT && pv != null) {
			for (int i = mSelectLeftView; i <= mSelectRightView; i++) {
				String t = pv.copy(i);
				if (t.length() == 0)
					continue;
				if (buffer.length() > 0)
					buffer.append("\n");
				buffer.append(t);
			}
		}
		return buffer.toString();
	}

	public void copy() {
		ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
		cm.setPrimaryClip(ClipData.newPlainText(null, getSelectedText()));
		if (Build.VERSION.SDK_INT < 33)   // newer Android shows its own "copied" notice
			Tool.toast(Tool.getResourceString(R.string.copied));
		finishAll();
	}

	public void endSelect() {
		if (mSelecting == SELECT.NO_SELECT) return;

		for (int i = mSelectLeftView; i <= mSelectRightView; i++) {
			PageView v = (PageView) mChildViews.get(i);

			if (v != null)
				v.unSelect();
			else if (mAdapter != null)
				mAdapter.unSelect(i);
		}

		if (mTextActionMode != null) {
			mTextActionMode.finish();
			mTextActionMode = null;
		}
		mAreaSelected = false;
		mSelecting = SELECT.NO_SELECT;
		updateBack();
	}

	public void share() {
		Intent intent = new Intent();
		intent.setAction(Intent.ACTION_SEND);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_TEXT, getSelectedText());
		getContext().startActivity(Intent.createChooser(intent, Tool.getResourceString(R.string.sharing_text)));
		finishAll();
	}

	public void selectAll() {
		if (getDisplayedView() == null) return;
		if (mCurrent == mSelectLeftView && mCurrent == mSelectRightView) {
			getDisplayedView().selectAll(Tool.BOTH_HANDLE);
		}
		else if (mCurrent == mSelectLeftView - 1) {
			getDisplayedView().selectAll(Tool.LEFT_HANDLE, Tool.HANDLE_EXTEND);
			PageView view = (PageView)mChildViews.get(mSelectLeftView);

			if (mSelectLeftView == mSelectRightView) {
				view.selectAll(Tool.RIGHT_HANDLE, Tool.HANDLE_NO_EXTEND);
			}
			else {
				view.selectAll(Tool.NO_HANDLE);
			}
			mSelectLeftView = mCurrent;
		}
		else if (mCurrent == mSelectLeftView) {
			getDisplayedView().selectAll(Tool.LEFT_HANDLE, Tool.HANDLE_EXTEND);
		}
		else if (mCurrent == mSelectRightView + 1) {
			getDisplayedView().selectAll(Tool.RIGHT_HANDLE, Tool.HANDLE_EXTEND);
			PageView view = (PageView)mChildViews.get(mSelectRightView);

			if (mSelectLeftView == mSelectRightView) {
				view.selectAll(Tool.LEFT_HANDLE, Tool.HANDLE_NO_EXTEND);
			}
			else {
				view.selectAll(Tool.NO_HANDLE);
			}
			mSelectRightView = mCurrent;
		}
		else if (mCurrent == mSelectRightView) {
			getDisplayedView().selectAll(Tool.RIGHT_HANDLE, Tool.HANDLE_EXTEND);
		}
	}

	public Rect getSelectionRect() {
		if (mAreaSelected) {
			Rect u = null;
			for (int k = mSelectLeftView; k <= mSelectRightView; k++) {
				PageView apv = (PageView) mChildViews.get(k);
				Rect r = apv != null ? apv.getAreaRect() : null;
				if (r == null) continue;
				if (u == null) u = new Rect(r); else u.union(r);
			}
			if (u != null)
				return u;
		}
		int i = 0;
		if (mCurrent >= mSelectLeftView && mCurrent <= mSelectRightView) {
			i = mCurrent;
		}
		else if (mCurrent < mSelectLeftView) {
			i = mSelectLeftView;
		}
		else if (mCurrent > mSelectRightView) {
			i = mSelectRightView;
		}
		PageView pv = getDisplayedView();
		if (pv == null || mAdapter == null)
			return new Rect(0, 0, getWidth(), getHeight());
		return pv.getSelectionRect(i);
	}

	private Runnable mHideFloatingToolbar = new Runnable() {
		@Override
		public void run() {
			if (mTextActionMode != null) {
				hideFloatingToolbar(10000);
				postDelayed(this, 1000);
			}
		}
	};

	private void hideFloatingToolbar(int duration) {
		mActionModeVisible = false;

		if (mTextActionMode != null) {
			mTextActionMode.hide(duration);
		}
	}

	private void showFloatingToolbar() {
		if (mTextActionMode != null && mActionModeVisible == false) {
			mActionModeVisible = true;
			mTextActionMode.invalidateContentRect();
			mTextActionMode.hide(0);
		}
	}

	private void updateFloatingToolbarInPage() {
		if (mTextActionMode != null) {
			if (mCurrent >= mSelectLeftView - 1 && mCurrent <= mSelectRightView + 1) {
				showFloatingToolbar();
			}
			else {
				post(mHideFloatingToolbar);
			}
		}
	}

	private void updateFloatingToolbarVisibility(MotionEvent event) {
		if (mTextActionMode != null) {
			int eventaction = event.getActionMasked();

			if (mBeginSelect) {
				if (eventaction == MotionEvent.ACTION_UP || eventaction == MotionEvent.ACTION_CANCEL) {
					mBeginSelect = false;
				}
				return;
			}
			switch (eventaction) {
			case MotionEvent.ACTION_MOVE:
				removeCallbacks(mHideFloatingToolbar);
				hideFloatingToolbar(ActionMode.DEFAULT_HIDE_DURATION);
				break;
			case MotionEvent.ACTION_UP:  // fall through
			case MotionEvent.ACTION_CANCEL:
				if (mSelecting == SELECT.MOVE_LEFT || mSelecting == SELECT.MOVE_RIGHT) {
					if (mMagnifier != null) {
						mMagnifier.dismiss();
					}
					showFloatingToolbar();
				}
			}
		}
	}

	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
			float velocityY) {
		if (mScaling || mAreaMode)
			return true;

		if (mLongPress)
			mLongPress = false;
		if (mSelecting != SELECT.NO_SELECT)
			mSelecting = SELECT.SELECTING;

		View v = mChildViews.get(mCurrent);
		if (v != null) {
			float offx = e1.getX() - e2.getX();
			float offy = e1.getY() - e2.getY();
			float vx = Math.abs(velocityX);
			float vy = Math.abs(velocityY);
			if (vx < vy) {
				scrollMore(Math.round(offx), Math.round(offy), velocityX, velocityY);
				return true;
			}
			mScrollerLastX = mScrollerLastY = 0;
			// If the page has been dragged out of bounds then we want to spring back
			// nicely. fling jumps back into bounds instantly, so we don't want to use
			// fling in that case. On the other hand, we don't want to forgo a fling
			// just because of a slightly off-angle drag taking us out of bounds other
			// than in the direction of the drag, so we test for out of bounds only
			// in the direction of travel.
			//
			// Also don't fling if out of bounds in any direction by more than fling
			// margin
			Rect bounds = getScrollBounds(v);
			Rect expandedBounds = new Rect(bounds);
			expandedBounds.inset(-FLING_MARGIN, -FLING_MARGIN);

			if(withinBoundsInDirectionOfTravel(bounds, velocityX, velocityY)
					&& expandedBounds.contains(0, 0)) {
				mScroller.fling(0, 0, (int)velocityX, (int)velocityY, bounds.left, bounds.right, bounds.top, bounds.bottom);
				mStepper.prod();
			}
		}

		return true;
	}

	public void onLongPress(MotionEvent e) { }

	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
			float distanceY) {
		if (mLongPress)
			mLongPress = false;
		if (mAreaMode)
			return true;   // one finger draws a box; two fingers pan / zoom through the scale detector
		if (mSelecting == SELECT.MOVE_LEFT || mSelecting == SELECT.MOVE_RIGHT) {
			moveSelect(e2.getX(), e2.getY());
			if (mMagnifier != null) {
				final int[] viewPosition = new int[2];
				this.getLocationOnScreen(viewPosition);
				mMagnifier.show(e2.getRawX() - viewPosition[0], e2.getRawY() - viewPosition[1]);
			}
			return true;
		}
		if (mSelecting != SELECT.NO_SELECT)
			mSelecting = SELECT.SELECTING;

		if (!mScaling) {
			mXScroll -= distanceX;
			mYScroll -= distanceY;
			requestLayout();
		}
		return true;
	}

	public void onShowPress(MotionEvent e) { }

	public boolean onScale(ScaleGestureDetector detector) {
		float previousScale = mScale;
		mScale = Math.min(Math.max(mScale * detector.getScaleFactor(), MIN_SCALE), MAX_SCALE);

		{
			float factor = mScale/previousScale;

			View v = mChildViews.get(mCurrent);
			if (v != null) {
				float currentFocusX = detector.getFocusX();
				float currentFocusY = detector.getFocusY();
				// Work out the focus point relative to the view top left
				int viewFocusX = (int)currentFocusX - (v.getLeft() + mXScroll);
				int viewFocusY = (int)currentFocusY - (v.getTop() + mYScroll);
				// Scroll to maintain the focus point
				mXScroll += viewFocusX - viewFocusX * factor;
				mYScroll += viewFocusY - viewFocusY * factor;

				if (mLastScaleFocusX>=0)
					mXScroll+=currentFocusX-mLastScaleFocusX;
				if (mLastScaleFocusY>=0)
					mYScroll+=currentFocusY-mLastScaleFocusY;

				mLastScaleFocusX=currentFocusX;
				mLastScaleFocusY=currentFocusY;
				requestLayout();
			}
		}
		return true;
	}

	public boolean onScaleBegin(ScaleGestureDetector detector) {
		if (mLongPress)
			mLongPress = false;
		if (mSelecting != SELECT.NO_SELECT)
			mSelecting = SELECT.SELECTING;

		tapDisabled = true;
		mScaling = true;
		// Ignore any scroll amounts yet to be accounted for: the
		// screen is not showing the effect of them, so they can
		// only confuse the user
		mXScroll = mYScroll = 0;
		mLastScaleFocusX = mLastScaleFocusY = -1;
		return true;
	}

	public void onScaleEnd(ScaleGestureDetector detector) {
		mScaling = false;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if ((event.getAction() & event.getActionMasked()) == MotionEvent.ACTION_DOWN)
		{
			tapDisabled = false;
		}
		updateFloatingToolbarVisibility(event);
		mScaleGestureDetector.onTouchEvent(event);
		mGestureDetector.onTouchEvent(event);
		if (mAreaMode)
			handleAreaEvent(event);

		if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
			mUserInteracting = true;
		}
		if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
			// move ended
			if (mSelecting == SELECT.MOVE_LEFT || mSelecting == SELECT.MOVE_RIGHT)
				mSelecting = SELECT.SELECTING;

			mUserInteracting = false;

			View v = mChildViews.get(mCurrent);
			if (v != null) {
				if (mScroller.isFinished()) {
					// If still there is no inertial scroll in operation
					// then the layout is stable
					postSettle(v);
				}
			}
		}

		return true;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		refresh();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int n = getChildCount();
		for (int i = 0; i < n; i++)
			measureView(getChildAt(i));
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		try {
			onLayout2(changed, left, top, right, bottom);
		}
		catch (java.lang.OutOfMemoryError e) {
			System.out.println("Out of memory during layout");
		}
	}

	private void onLayout2(boolean changed, int left, int top, int right,
			int bottom) {

		if (isInEditMode() || mAdapter == null)
			return;

		View cv = mChildViews.get(mCurrent);

		if (!mResetLayout) {
			// Move to next or previous if current is sufficiently off center
			if (cv != null) {
				boolean move;
				// cv.getBottom() may be out of date with the current scale
				// so add top to the measured height for the correct position
				move = cv.getTop() + cv.getMeasuredHeight() + GAP/2 + mYScroll < getHeight()/2;
				if (move && mCurrent + 1 < mAdapter.getCount()) {
					postUnsettle(cv);
					// post to invoke test for end of animation
					// where we must set hq area for the new current view
					mStepper.prod();

					mCurrent++;
				}

				move = cv.getTop() - GAP/2 + mYScroll >= getHeight()/2;
				if (move && mCurrent > 0) {
					postUnsettle(cv);
					mStepper.prod();

					mCurrent--;
				}
			}

			// Remove not needed children and hold them for reuse
			int numChildren = mChildViews.size();
			int childIndices[] = new int[numChildren];
			for (int i = 0; i < numChildren; i++)
				childIndices[i] = mChildViews.keyAt(i);

			for (int i = 0; i < numChildren; i++) {
				int ai = childIndices[i];
				if (ai < mCurrent - 3 || ai > mCurrent + 3) {
					View v = mChildViews.get(ai);
					onNotInUse(v);
					mViewCache.add(v);
					removeViewInLayout(v);
					mChildViews.remove(ai);
				}
			}
		} else {
			mXScroll = mYScroll = 0;

			// Remove all children and hold them for reuse
			int numChildren = mChildViews.size();
			for (int i = 0; i < numChildren; i++) {
				View v = mChildViews.valueAt(i);
				onNotInUse(v);
				mViewCache.add(v);
				removeViewInLayout(v);
			}
			mChildViews.clear();

			// post to ensure generation of hq area
			mStepper.prod();
		}

		// Ensure current view is present
		int cvLeft, cvRight, cvTop, cvBottom;
		boolean notPresent = (mChildViews.get(mCurrent) == null);
		cv = getOrCreateChild(mCurrent);
		if (notPresent) {
			if (mResetLayout) {
				cvLeft = mPrevLeft;
				cvTop = mPrevTop;
			}
			else {
				// Main item not already present. Just place it top left
				cvLeft = 0;
				cvTop = 0;
				// center a page shorter than the screen vertically
				if (cv.getMeasuredHeight() < getHeight()) {
					cvTop += (getHeight() - cv.getMeasuredHeight()) / 2;
				}
			}
		} else {
			// check for scroll outside page
			mXScroll = validateXScroll(cv, mXScroll);
			mYScroll = validateYBeyond(cv, mYScroll);
			// Main item already present. Adjust by scroll offsets
			cvLeft = cv.getLeft() + mXScroll;
			cvTop = cv.getTop() + mYScroll;
		}
		cvRight = cvLeft + cv.getMeasuredWidth();
		cvBottom = cvTop + cv.getMeasuredHeight();
		Point corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom));

		if (cv.getMeasuredWidth() <= getWidth()) {
			// When the current view is as small as the screen in width, clamp
			// it horizontally
			cvLeft += corr.x;
			cvRight += corr.x;
		}

		cv.layout(cvLeft, cvTop, cvRight, cvBottom);
		mResetLayout = false;

		if (mCurrent > 0) {
			View av = aboveView(cv, -1, true);
			if (av != null && mCurrent > 1) {
				View av2 = aboveView(av, -2, true);
				if (av2 != null && mCurrent > 2)
					aboveView(av2, -3, false);
			}
		}

		if (mCurrent + 1 < mAdapter.getCount()) {
			View bv = belowView(cv, 1, true);
			if (bv != null && mCurrent + 2 < mAdapter.getCount()) {
				View bv2 = belowView(bv, 2, true);
				if (bv2 != null && mCurrent + 3 < mAdapter.getCount())
					belowView(bv2, 3, false);
			}
		}

		mXScroll = mYScroll = 0;
		invalidate();

		if (mPageListener != null && mCurrent != mReportedPage) {
			mReportedPage = mCurrent;
			final PageListener l = mPageListener;
			final int page = mCurrent;
			final int count = mAdapter.getCount();
			// posted: the listener updates views, which must not happen mid-layout
			post(new Runnable() {
				public void run() { l.onPageChanged(page, count); }
			});
		}
	}

	// lay out the page above v
	private View aboveView(View v, int n, boolean force) {
		View v2 = null;
		if (force || mYScroll > 0) {                 // move content to bottom
			v2 = getOrCreateChild(mCurrent + n);
			v2.layout((v.getLeft() + v.getRight() - v2.getMeasuredWidth()) / 2
					, v.getTop() - GAP - v2.getMeasuredHeight()
					, (v.getLeft() + v.getRight() + v2.getMeasuredWidth()) / 2
					, v.getTop() - GAP
					);
		}
		else {
			rmExtraView(mChildViews.get(mCurrent + n), mCurrent + n);
		}
		return v2;
	}

	// lay out the page below v
	private View belowView(View v, int n, boolean force) {
		View v2 = null;
		if (force || mYScroll < 0) {                 // move content to top
			v2 = getOrCreateChild(mCurrent + n);
			v2.layout((v.getLeft() + v.getRight() - v2.getMeasuredWidth()) / 2
					, v.getBottom() + GAP
					, (v.getLeft() + v.getRight() + v2.getMeasuredWidth()) / 2
					, v.getBottom() + GAP + v2.getMeasuredHeight()
					);
		}
		else {
			rmExtraView(mChildViews.get(mCurrent + n), mCurrent + n);
		}
		return v2;
	}

	private void rmExtraView(View v, int ai) {
		if (v == null) return;
		onNotInUse(v);
		mViewCache.add(v);
		removeViewInLayout(v);
		mChildViews.remove(ai);
	}

	@Override
	public Adapter getAdapter() {
		return mAdapter;
	}

	@Override
	public View getSelectedView() {
		return null;
	}

	@Override
	public void setAdapter(Adapter adapter) {
		if (mAdapter != null && mAdapter != adapter)
			mAdapter.releaseBitmaps();
		mAdapter = (PageAdapter) adapter;

		requestLayout();
	}

	@Override
	public void setSelection(int arg0) {
		throw new UnsupportedOperationException(getContext().getString(R.string.not_supported));
	}

	private View getCached() {
		if (mViewCache.size() == 0)
			return null;
		else
			return mViewCache.removeFirst();
	}

	private View getOrCreateChild(int i) {
		View v = mChildViews.get(i);
		if (v == null) {
			v = mAdapter.getView(i, getCached(), this);
			addAndMeasureChild(i, v);
		}

		return v;
	}

	private void addAndMeasureChild(int i, View v) {
		LayoutParams params = v.getLayoutParams();
		if (params == null) {
			params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
		}
		addViewInLayout(v, 0, params, true);
		mChildViews.append(i, v); // Record the view against its adapter index
		measureView(v);
	}

	private void measureView(View v) {
		// See what size the view wants to be
		v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

		// Fit the page width to the screen
		float scale = (float)getWidth()/(float)v.getMeasuredWidth();
		// Use the fitting values scaled by our current scale factor
		v.measure(View.MeasureSpec.EXACTLY | (int)(v.getMeasuredWidth()*scale*mScale),
				View.MeasureSpec.EXACTLY | (int)(v.getMeasuredHeight()*scale*mScale));
	}

	private Rect getScrollBounds(int left, int top, int right, int bottom) {
		int xmin = getWidth() - right;
		int xmax = -left;
		int ymin = getHeight() - bottom;
		int ymax = -top;

		// In either dimension, if view smaller than screen then
		// constrain it to be central
		if (xmin > xmax) xmin = xmax = (xmin + xmax)/2;
		if (ymin > ymax) ymin = ymax = (ymin + ymax)/2;

		return new Rect(xmin, ymin, xmax, ymax);
	}

	private Rect getScrollBounds(View v) {
		// There can be scroll amounts not yet accounted for in
		// onLayout, so add mXScroll and mYScroll to the current
		// positions when calculating the bounds.
		return getScrollBounds(v.getLeft() + mXScroll,
				v.getTop() + mYScroll,
				v.getLeft() + v.getMeasuredWidth() + mXScroll,
				v.getTop() + v.getMeasuredHeight() + mYScroll);
	}

	private Point getCorrection(Rect bounds) {
		return new Point(Math.min(Math.max(0,bounds.left),bounds.right),
				Math.min(Math.max(0,bounds.top),bounds.bottom));
	}

	private void postSettle(final View v) {
		// onSettle and onUnsettle are posted so that the calls
		// won't be executed until after the system has performed
		// layout.
		post (new Runnable() {
			public void run () {
				onSettle(v);
			}
		});
	}

	private void postUnsettle(final View v) {
		post (new Runnable() {
			public void run () {
				onUnsettle(v);
			}
		});
	}

	private static int directionOfTravel(float vx, float vy) {
		if (Math.abs(vx) > 2 * Math.abs(vy))
			return (vx > 0) ? MOVING_RIGHT : MOVING_LEFT;
		else if (Math.abs(vy) > 2 * Math.abs(vx))
			return (vy > 0) ? MOVING_DOWN : MOVING_UP;
		else
			return MOVING_DIAGONALLY;
	}

	private static boolean withinBoundsInDirectionOfTravel(Rect bounds, float vx, float vy) {
		switch (directionOfTravel(vx, vy)) {
		case MOVING_DIAGONALLY: return bounds.contains(0, 0);
		case MOVING_LEFT: return bounds.left <= 0;
		case MOVING_RIGHT: return bounds.right >= 0;
		case MOVING_UP: return bounds.top <= 0;
		case MOVING_DOWN: return bounds.bottom >= 0;
		default: throw new NoSuchElementException();
		}
	}

	public boolean onSingleTapUp(MotionEvent e) {
		if (mLongPress)
			mLongPress = false;
		if (mSelecting == SELECT.SELECTING)
			return true;
		if (mSelecting == SELECT.MOVE_NONE) {
			endSelect();
			return true;
		}
		if (mSelecting != SELECT.NO_SELECT) {
			// MOVE_LEFT, MOVE_RIGHT, MOVE_IN
			showFloatingToolbar();
			mSelecting = SELECT.SELECTING;
		}

		if (!tapDisabled) {
			// tap near the top / bottom edge pages up / down
			if (e.getY() < tapPageMargin) {
				smartMoveBackwards();
			} else if (e.getY() > super.getHeight() - tapPageMargin) {
				smartMoveForwards();
			}
		}
		return true;
	}

	private boolean pointInView(float x, float y, View v) {
		return (x > v.getLeft() && x < v.getRight() && y > v.getTop() && y < v.getBottom());
	}

	/**
	 * before the view is rebuilt, save current position
	 */
	private void savePosition() {
		View cv = getDisplayedView();
		if (cv == null) return;
		mPrevLeft = cv.getLeft();
		mPrevTop = cv.getTop();
	}

	/**
	 * do not allow scroll beyond left and right side
	 */
	private int validateXScroll(View v, int xScroll) {
		if (xScroll > 0) {                          // move content to right
			int left = -(v.getLeft());
			if (xScroll > left) xScroll = left;
		}
		else if (xScroll < 0) {                     // move content to left
			int right = -(v.getRight() - getWidth());
			if (xScroll < right) xScroll = right;
		}
		return xScroll;
	}

	/**
	 * do not allow scroll beyond first and last view
	 */
	private int validateYBeyond(View v, int yScroll) {
		boolean hclam = v.getMeasuredHeight() < getHeight();
		int clam = (getHeight() - v.getMeasuredHeight()) / 2;
		if (mCurrent == 0) {
			if (yScroll > 0) {
				int top = -(v.getTop());
				if (hclam) top += clam;
				if (yScroll > top) yScroll = top;
			}
		}
		if (mCurrent == mAdapter.getCount() - 1) {
			if (yScroll < 0) {
				int bottom = -(v.getBottom() - getHeight());
				if (hclam) bottom -= clam;
				if (yScroll < bottom) yScroll = bottom;
			}
		}
		return yScroll;
	}

	/**
	 * continuous scroll
	 */
	private void scrollMore(int xOffset, int yOffset, float xv, float yv) {
		View v = mChildViews.get(mCurrent);
		if (v == null) return;

		int speed = 2000;

		yOffset += (int)(v.getHeight() * yv / speed);

		mScrollerLastX = mScrollerLastY = 0;
		mScroller.fling(0, 0, (int)xv, (int)yv
				, Math.min(0, xOffset)
				, Math.max(0, xOffset)
				, Math.min(0, yOffset)
				, Math.max(0, yOffset));
		mStepper.prod();
	}

	protected void onSettle(View v) {
		// When the layout has settled ask the page to render
		// in HQ
		// this will update from original data when zoomed to make text clear
		((PageView) v).updateHq(false);
		if (mCurrent > 0) {
			View v1 = mChildViews.get(mCurrent - 1);
			if (v1 != null && (v1.getTop() + v1.getMeasuredHeight()) > 0) {
				((PageView) v1).updateHq(false);
			}
		}
		if (mCurrent < mAdapter.getCount() - 1) {
			View v2 = mChildViews.get(mCurrent + 1);
			if (v2 != null && v2.getTop() < getHeight()) {
				((PageView) v2).updateHq(false);
			}
		}
	}

	protected void onUnsettle(View v) {
		// When something changes making the previous settled view
		// no longer appropriate, tell the page to remove HQ
		((PageView) v).removeHq();
		if (mCurrent > 0) {
			View v1 = mChildViews.get(mCurrent - 1);
			if (v1 != null)
				((PageView) v1).removeHq();
		}
		if (mCurrent < mAdapter.getCount() - 1) {
			View v2 = mChildViews.get(mCurrent + 1);
			if (v2 != null)
				((PageView) v2).removeHq();
		}
	}

	protected void onNotInUse(View v) {
		((PageView) v).releaseResources();
	}
}
