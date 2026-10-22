package net.timelegend.chaka.viewer;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

public class PageAdapter extends BaseAdapter {
	private final Context mContext;
	private final MuPDFCore mCore;
	private final SparseArray<PointF> mPageSizes = new SparseArray<PointF>();

	public PageAdapter(Context c, MuPDFCore core) {
		mContext = c;
		mCore = core;
	}

	public MuPDFCore getCore() {
		return mCore;
	}

	public int getCount() {
		return mCore.countPages();
	}

	public Object getItem(int position) {
		return null;
	}

	public long getItemId(int position) {
		return 0;
	}

	public synchronized void releaseBitmaps()
	{
	}

	public void unSelect(int position) {
		mCore.rmTSModel(position);
	}

	public void refresh() {
		mPageSizes.clear();
		mCore.clearTSModel();
	}

	public synchronized View getView(final int position, View convertView, ViewGroup parent) {
		final PageView pageView;
		if (convertView == null) {
			// screen width and height
			pageView = new PageView(mContext, mCore, new Point(parent.getWidth(), parent.getHeight()));
		} else {
			pageView = (PageView) convertView;
		}

		PointF pageSize = mPageSizes.get(position);
		if (pageSize != null) {
			// We already know the page size. Set it up
			// immediately
			pageView.setPage(position, pageSize);
		} else {
			// Page size as yet unknown. Blank it for now, and
			// start a background task to find the size
			pageView.blank(position);
			AsyncTask<Void,Void,PointF> sizingTask = new AsyncTask<Void,Void,PointF>() {
				@Override
				protected PointF doInBackground(Void... arg0) {
					try {
						return mCore.getPageSize(position);
					} catch (RuntimeException e) {
						return null;
					}
				}

				@Override
				protected void onPostExecute(PointF result) {
					super.onPostExecute(result);
					// We now know the page size
					mPageSizes.put(position, result);
					// Check that this view hasn't been reused for
					// another page since we started
					if (pageView.getPage() == position)
						pageView.setPage(position, result);
				}
			};

			sizingTask.execute((Void)null);
		}
		return pageView;
	}
}
