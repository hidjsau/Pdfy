package net.timelegend.chaka.viewer;

import com.artifex.mupdf.fitz.SeekableInputStream;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The only screen: shows a button to pick a PDF (and opens the picker
 * automatically), or opens a PDF handed over by another app.
 */
public class DocumentActivity extends AppCompatActivity
{
	private static final String PDF_MIME = "application/pdf";

	private MuPDFCore core;
	private ReaderView docView;
	private EditText passwordView;
	private ActivityResultLauncher<String[]> picker;
	private boolean pickerShown = false;

	private Uri currentUri;
	private String currentName;
	private boolean external = false;           // opened by another app (Back should exit)
	private ArrayList<MuPDFCore.OutlineItem> outline;
	private TextView pageIndicator;
	private TextView contentsButton;
	private TextView banner;
	private LinearLayout multiBar;
	private TextView multiCount;
	private Runnable hideBannerTask;
	private OnBackPressedCallback docBackCallback;

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		Tool.create(this);

		picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
			if (uri != null) {
				keepAccess(uri);
				openUri(uri);
			}
		});

		// Back from a document returns to the start screen / recent files
		docBackCallback = new OnBackPressedCallback(false) {
			@Override
			public void handleOnBackPressed() {
				rememberPosition();
				closeCore();
				showStartScreen();
			}
		};
		getOnBackPressedDispatcher().addCallback(this, docBackCallback);

		showStartScreen();

		Intent intent = getIntent();
		if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
			external = true;
			keepAccess(intent.getData());
			openUri(intent.getData());
		}
		else if (savedInstanceState == null && RecentFiles.load(this).isEmpty()) {
			pickerShown = true;
			picker.launch(new String[] { PDF_MIME });
		}
	}

	/** Ask for long-term read access so the file can be reopened from recent files. */
	private void keepAccess(Uri uri) {
		try {
			getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
		} catch (Exception x) {
			// not every provider offers persistable grants; ignore
		}
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private void showStartScreen() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
			Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(b.left, b.top, b.right, b.bottom);
			return insets;
		});

		final List<RecentFiles.Entry> recent = RecentFiles.load(this);

		Button open = new Button(this);
		open.setText(R.string.open_pdf);
		open.setOnClickListener(v -> picker.launch(new String[] { PDF_MIME }));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		if (recent.isEmpty()) {
			blp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
			blp.gravity = Gravity.CENTER;
			blp.topMargin = dp(0);
			root.setGravity(Gravity.CENTER);
		} else {
			blp.gravity = Gravity.CENTER_HORIZONTAL;
			blp.topMargin = dp(16);
		}
		root.addView(open, blp);

		if (!recent.isEmpty()) {
			TextView header = new TextView(this);
			header.setText(R.string.recent_files);
			header.setTextSize(14);
			header.setTextColor(0xFF3CB371);
			header.setPadding(dp(20), dp(20), dp(20), dp(8));
			root.addView(header);

			ListView list = new ListView(this);
			list.setAdapter(new ArrayAdapter<RecentFiles.Entry>(this, 0, recent) {
				@Override
				public View getView(int position, View convertView, ViewGroup parent) {
					RecentFiles.Entry e = getItem(position);
					LinearLayout row = new LinearLayout(DocumentActivity.this);
					row.setOrientation(LinearLayout.VERTICAL);
					row.setPadding(dp(20), dp(12), dp(20), dp(12));
					TextView name = new TextView(DocumentActivity.this);
					name.setText(e.name);
					name.setTextSize(16);
					name.setSingleLine(true);
					name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
					TextView sub = new TextView(DocumentActivity.this);
					sub.setText(String.format(Locale.ROOT, getString(R.string.page_of),
							e.page + 1, Math.max(e.pages, e.page + 1)));
					sub.setTextSize(13);
					sub.setTextColor(0xFF757575);
					row.addView(name);
					row.addView(sub);
					return row;
				}
			});
			list.setOnItemClickListener((p, v, pos, id) -> openUri(Uri.parse(recent.get(pos).uri)));
			list.setOnItemLongClickListener((p, v, pos, id) -> {
				final RecentFiles.Entry e = recent.get(pos);
				new AlertDialog.Builder(this, R.style.MyDialog)
						.setTitle(R.string.remove_recent)
						.setMessage(e.name)
						.setPositiveButton(R.string.remove, (d, w) -> {
							RecentFiles.remove(this, e.uri);
							showStartScreen();
						})
						.setNegativeButton(R.string.cancel, null)
						.show();
				return true;
			});
			root.addView(list, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		}
		setContentView(root);
	}

	private void closeCore() {
		docBackCallback.setEnabled(false);
		outline = null;
		pageIndicator = null;
		contentsButton = null;
		banner = null;
		multiBar = null;
		multiCount = null;
		if (docView != null) {
			docView.getBackCallback().remove();
			docView = null;
		}
		if (core != null) {
			core.onDestroy();
			core = null;
		}
	}

	private void openUri(Uri uri) {
		rememberPosition();
		closeCore();
		currentUri = uri;
		currentName = queryName(uri);
		try {
			core = openCore(uri);
		}
		catch (Throwable x) {
			core = null;
			// unreadable or gone: drop it from recent files
			RecentFiles.remove(this, uri.toString());
			showStartScreen();
			showError(x.toString());
			return;
		}

		if (core == null || core.needsPassword()) {
			if (core == null)
				showError(null);
			else
				requestPassword();
			return;
		}
		showDocument();
	}

	private String queryName(Uri uri) {
		try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
			if (c != null && c.moveToFirst()) {
				int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
				if (idx >= 0) {
					String n = c.getString(idx);
					if (n != null && n.length() > 0)
						return n;
				}
			}
		} catch (Exception x) {
			// fall through
		}
		String last = uri.getLastPathSegment();
		return last != null ? last : uri.toString();
	}

	/** Save the page being read so it can be resumed later. */
	private void rememberPosition() {
		if (core != null && docView != null && currentUri != null)
			RecentFiles.record(this, currentUri.toString(), currentName,
					docView.getCurrentPage(), core.countPages());
	}

	private MuPDFCore openCore(Uri uri) throws IOException {
		ContentResolver cr = getContentResolver();

		long size = -1;
		try (android.database.Cursor cursor = cr.query(uri, null, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				int idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
				if (idx >= 0 && cursor.getType(idx) == android.database.Cursor.FIELD_TYPE_INTEGER)
					size = cursor.getLong(idx);
				if (size == 0)
					size = -1;
			}
		} catch (Exception x) {
			// ignore, depend on default size
		}

		byte[] buf = null;
		int used = -1;
		InputStream is = cr.openInputStream(uri);
		try {
			final int limit = 8 * 1024 * 1024;
			if (size < 0) { // size is unknown
				buf = new byte[limit];
				used = is.read(buf);
				boolean atEOF = is.read() == -1;
				if (used < 0 || (used == limit && !atEOF)) // no or partial data
					buf = null;
			} else if (size <= limit) { // size is known and below limit
				buf = new byte[(int) size];
				used = is.read(buf);
				if (used < 0 || used < size) // no or partial data
					buf = null;
			}
			if (buf != null && buf.length != used) {
				byte[] newbuf = new byte[used];
				System.arraycopy(buf, 0, newbuf, 0, used);
				buf = newbuf;
			}
		} catch (OutOfMemoryError e) {
			buf = null;
		} finally {
			is.close();
		}

		if (buf != null) {
			Tool.i("Opening document from memory buffer of size " + buf.length);
			return new MuPDFCore(buf, PDF_MIME);
		} else {
			Tool.i("Opening document from stream");
			SeekableInputStream stm = new ContentInputStream(cr, uri, size);
			return new MuPDFCore(stm, PDF_MIME);
		}
	}

	private void showError(String reason) {
		AlertDialog alert = new AlertDialog.Builder(this, R.style.MyDialog).create();
		if (reason == null)
			alert.setTitle(R.string.cannot_open_document);
		else
			alert.setTitle(String.format(Locale.ROOT, getString(R.string.cannot_open_document_Reason), reason));
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
					}
				});
		alert.show();
	}

	private void requestPassword() {
		passwordView = new EditText(this);
		passwordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		passwordView.setTransformationMethod(new PasswordTransformationMethod());

		AlertDialog alert = new AlertDialog.Builder(this, R.style.MyDialog).create();
		alert.setTitle(R.string.enter_password);
		alert.setView(passwordView);
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						if (core.authenticatePassword(passwordView.getText().toString())) {
							showDocument();
						} else {
							requestPassword();
						}
					}
				});
		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						closeCore();
					}
				});
		alert.show();
	}

	private void showDocument() {
		if (core == null || core.countPages() <= 0) {
			closeCore();
			showError(null);
			return;
		}
		final MuPDFCore c = core;
		final int count = c.countPages();

		// resume where the person left off
		int start = 0;
		RecentFiles.Entry saved = RecentFiles.find(this, currentUri.toString());
		if (saved != null)
			start = Math.min(Math.max(saved.page, 0), count - 1);

		docView = new ReaderView(this);
		docView.setAdapter(new PageAdapter(this, core));
		if (start > 0)
			docView.goToPage(start);
		getOnBackPressedDispatcher().addCallback(this, docView.getBackCallback());
		docBackCallback.setEnabled(!external);

		final FrameLayout frame = new FrameLayout(this);
		frame.addView(docView, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		// page indicator ("12 / 240"); tap to jump to a page
		pageIndicator = makeOverlay(14, 6);
		pageIndicator.setText(String.format(Locale.ROOT, "%d / %d", start + 1, count));
		pageIndicator.setOnClickListener(v -> showGoToDialog());
		FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		ilp.bottomMargin = dp(12);
		frame.addView(pageIndicator, ilp);

		// table of contents button, shown only if the document has an outline
		contentsButton = makeOverlay(18, 8);
		contentsButton.setText("\u2630");
		contentsButton.setVisibility(View.GONE);
		contentsButton.setOnClickListener(v -> showOutlineDialog());
		FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.TOP | Gravity.END);
		clp.topMargin = dp(12);
		clp.rightMargin = dp(12);
		frame.addView(contentsButton, clp);

		// short notice near the bottom edge (used for the area selection tool)
		banner = makeOverlay(14, 10);
		banner.setVisibility(View.GONE);
		banner.setMaxWidth(dp(340));
		banner.setGravity(Gravity.CENTER);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		blp.bottomMargin = dp(112);
		frame.addView(banner, blp);

		// bar shown while "Select multiple" is holding selections: count, Copy, Clear
		multiBar = new LinearLayout(this);
		multiBar.setOrientation(LinearLayout.HORIZONTAL);
		multiBar.setGravity(Gravity.CENTER_VERTICAL);
		GradientDrawable mbg = new GradientDrawable();
		mbg.setColor(0xE6202020);
		mbg.setCornerRadius(dp(24));
		multiBar.setBackground(mbg);
		multiBar.setPadding(dp(16), dp(2), dp(4), dp(2));
		multiBar.setVisibility(View.GONE);
		multiCount = new TextView(this);
		multiCount.setTextColor(Color.WHITE);
		multiCount.setTextSize(14);
		multiBar.addView(multiCount);
		TextView copyBtn = new TextView(this);
		copyBtn.setText(R.string.copy);
		copyBtn.setTextColor(0xFF7CE0A5);
		copyBtn.setTextSize(14);
		copyBtn.setTypeface(copyBtn.getTypeface(), android.graphics.Typeface.BOLD);
		copyBtn.setPadding(dp(16), dp(10), dp(12), dp(10));
		copyBtn.setOnClickListener(v -> { if (docView != null) docView.copy(); });
		multiBar.addView(copyBtn);
		TextView clearBtn = new TextView(this);
		clearBtn.setText(R.string.clear);
		clearBtn.setTextColor(0xFFCCCCCC);
		clearBtn.setTextSize(14);
		clearBtn.setPadding(dp(12), dp(10), dp(14), dp(10));
		clearBtn.setOnClickListener(v -> { if (docView != null) docView.clearMulti(); });
		multiBar.addView(clearBtn);
		FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		mlp.bottomMargin = dp(60);
		frame.addView(multiBar, mlp);

		ViewCompat.setOnApplyWindowInsetsListener(frame, (v, insets) -> {
			Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			if (banner != null) {
				FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) banner.getLayoutParams();
				lp.bottomMargin = b.bottom + dp(112);
				banner.setLayoutParams(lp);
			}
			if (multiBar != null) {
				FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) multiBar.getLayoutParams();
				lp.bottomMargin = b.bottom + dp(60);
				multiBar.setLayoutParams(lp);
			}
			if (pageIndicator != null) {
				FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pageIndicator.getLayoutParams();
				lp.bottomMargin = b.bottom + dp(12);
				pageIndicator.setLayoutParams(lp);
			}
			if (contentsButton != null) {
				FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) contentsButton.getLayoutParams();
				lp.topMargin = b.top + dp(12);
				lp.rightMargin = b.right + dp(12);
				contentsButton.setLayoutParams(lp);
			}
			return insets;
		});

		docView.setAreaModeListener(active -> {
			showBanner(getString(active ? R.string.area_on : R.string.area_off));
			if (pageIndicator != null)   // green page pill = area tool is on
				((GradientDrawable) pageIndicator.getBackground()).setColor(active ? 0xE63CB371 : 0xB3000000);
		});

		docView.setMultiListener(count -> {
			if (multiBar == null)
				return;
			if (count > 0) {
				multiCount.setText(String.format(Locale.ROOT, getString(R.string.multi_selected), count));
				multiBar.setVisibility(View.VISIBLE);
			} else {
				multiBar.setVisibility(View.GONE);
			}
		});

		docView.setPageListener((page, total) -> {
			if (pageIndicator != null)
				pageIndicator.setText(String.format(Locale.ROOT, "%d / %d", page + 1, total));
		});

		setContentView(frame);
		ViewCompat.requestApplyInsets(frame);

		RecentFiles.record(this, currentUri.toString(), currentName, start, count);
		loadOutlineAsync(c);
	}

	private TextView makeOverlay(int textSp, int padVdp) {
		TextView t = new TextView(this);
		t.setTextColor(Color.WHITE);
		t.setTextSize(textSp);
		t.setPadding(dp(14), dp(padVdp), dp(14), dp(padVdp));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xB3000000);
		bg.setCornerRadius(dp(24));
		t.setBackground(bg);
		return t;
	}

	private void loadOutlineAsync(final MuPDFCore c) {
		new Thread(() -> {
			final ArrayList<MuPDFCore.OutlineItem> items = c.getOutline();
			runOnUiThread(() -> {
				if (core != c || contentsButton == null)
					return;
				outline = items;
				if (!items.isEmpty())
					contentsButton.setVisibility(View.VISIBLE);
			});
		}).start();
	}

	private void showGoToDialog() {
		if (docView == null || core == null)
			return;
		final int count = core.countPages();
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setSingleLine(true);
		input.setImeOptions(EditorInfo.IME_ACTION_GO);
		input.setHint(String.format(Locale.ROOT, getString(R.string.page_range_hint), count));
		input.setText(String.valueOf(docView.getCurrentPage() + 1));
		input.selectAll();

		FrameLayout holder = new FrameLayout(this);
		holder.setPadding(dp(24), dp(8), dp(24), 0);
		holder.addView(input);

		final AlertDialog dialog = new AlertDialog.Builder(this, R.style.MyDialog)
				.setTitle(R.string.go_to_page)
				.setView(holder)
				.setPositiveButton(R.string.go, (d, w) -> {
					try {
						int n = Integer.parseInt(input.getText().toString().trim());
						if (docView != null)
							docView.goToPage(Math.min(Math.max(n, 1), count) - 1);
					} catch (NumberFormatException x) {
						// empty or invalid: stay where we are
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.create();
		input.setOnEditorActionListener((v, actionId, event) -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
			return true;
		});
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		dialog.show();
		input.requestFocus();
	}

	private void showBanner(String text) {
		final TextView t = banner;
		if (t == null)
			return;
		if (hideBannerTask != null)
			t.removeCallbacks(hideBannerTask);
		t.animate().cancel();
		t.setText(text);
		t.setAlpha(1f);
		t.setVisibility(View.VISIBLE);
		hideBannerTask = () -> t.animate().alpha(0f).setDuration(300)
				.withEndAction(() -> t.setVisibility(View.GONE));
		t.postDelayed(hideBannerTask, 3500);
	}

	/** Small right-pointing chevron; rotated 90 degrees when its chapter is expanded. */
	private class ChevronView extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();

		ChevronView(Context c) {
			super(c);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(2));
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setColor(0xFF616161);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(5);
			path.reset();
			path.moveTo(cx - s * 0.6f, cy - s);
			path.lineTo(cx + s * 0.6f, cy);
			path.lineTo(cx - s * 0.6f, cy + s);
			canvas.drawPath(path, paint);
		}
	}

	/** Contents list: chapters with sub-sections that collapse and expand. */
	private class OutlineAdapter extends BaseAdapter {
		private final ArrayList<MuPDFCore.OutlineItem> items;
		private final HashSet<Integer> collapsed = new HashSet<>();
		private final ArrayList<Integer> visible = new ArrayList<>();
		private final int currentIndex;   // entry for the page being read, or -1

		OutlineAdapter(ArrayList<MuPDFCore.OutlineItem> items, int currentPage) {
			this.items = items;

			// start with every chapter that has sub-sections collapsed ...
			for (int i = 0; i < items.size(); i++)
				if (hasChildren(i))
					collapsed.add(i);

			// ... except the path leading to the section being read
			int cur = -1;
			for (int i = 0; i < items.size(); i++) {
				int pg = items.get(i).page;
				if (pg >= 0 && pg <= currentPage)
					cur = i;
			}
			currentIndex = cur;
			if (cur >= 0) {
				int level = items.get(cur).level;
				for (int j = cur - 1; j >= 0 && level > 0; j--) {
					if (items.get(j).level < level) {
						collapsed.remove(j);
						level = items.get(j).level;
					}
				}
			}
			rebuild();
		}

		boolean hasChildren(int i) {
			return i + 1 < items.size() && items.get(i + 1).level > items.get(i).level;
		}

		private void rebuild() {
			visible.clear();
			int skipLevel = -1;
			for (int i = 0; i < items.size(); i++) {
				MuPDFCore.OutlineItem it = items.get(i);
				if (skipLevel >= 0 && it.level > skipLevel)
					continue;
				skipLevel = -1;
				visible.add(i);
				if (collapsed.contains(i))
					skipLevel = it.level;
			}
		}

		int visiblePositionOfCurrent() {
			return currentIndex < 0 ? -1 : visible.indexOf(currentIndex);
		}

		MuPDFCore.OutlineItem itemAt(int position) {
			return items.get(visible.get(position));
		}

		void toggleAt(int position) {
			int idx = visible.get(position);
			if (!hasChildren(idx))
				return;
			if (!collapsed.remove(idx))
				collapsed.add(idx);
			rebuild();
			notifyDataSetChanged();
		}

		@Override public int getCount() { return visible.size(); }
		@Override public Object getItem(int position) { return items.get(visible.get(position)); }
		@Override public long getItemId(int position) { return visible.get(position); }

		@Override
		public View getView(final int position, View convertView, ViewGroup parent) {
			final int idx = visible.get(position);
			MuPDFCore.OutlineItem it = items.get(idx);
			boolean children = hasChildren(idx);

			LinearLayout row = new LinearLayout(DocumentActivity.this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(8 + Math.min(it.level, 6) * 20), dp(2), dp(16), dp(2));

			if (children) {
				ChevronView chev = new ChevronView(DocumentActivity.this);
				chev.setRotation(collapsed.contains(idx) ? 0f : 90f);
				chev.setOnClickListener(v -> toggleAt(position));
				row.addView(chev, new LinearLayout.LayoutParams(dp(40), dp(44)));
			} else {
				row.addView(new View(DocumentActivity.this), new LinearLayout.LayoutParams(dp(40), dp(44)));
			}

			TextView title = new TextView(DocumentActivity.this);
			title.setText(it.title);
			title.setTextSize(it.level == 0 ? 16 : 15);
			if (it.level == 0)
				title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
			if (idx == currentIndex)
				title.setTextColor(0xFF2E8B57);
			else if (it.page < 0)
				title.setTextColor(0xFF757575);
			row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

			if (it.page >= 0) {
				TextView pg = new TextView(DocumentActivity.this);
				pg.setText(String.valueOf(it.page + 1));
				pg.setTextSize(13);
				pg.setTextColor(0xFF757575);
				pg.setPadding(dp(12), 0, 0, 0);
				row.addView(pg);
			}
			return row;
		}
	}

	private void showOutlineDialog() {
		if (outline == null || outline.isEmpty() || docView == null)
			return;
		final OutlineAdapter adapter = new OutlineAdapter(outline, docView.getCurrentPage());
		final ListView list = new ListView(this);
		list.setAdapter(adapter);

		final AlertDialog dialog = new AlertDialog.Builder(this, R.style.MyDialog)
				.setTitle(R.string.contents)
				.setView(list)
				.setNegativeButton(R.string.cancel, null)
				.create();
		list.setOnItemClickListener((p, v, pos, id) -> {
			MuPDFCore.OutlineItem it = adapter.itemAt(pos);
			if (it.page >= 0 && docView != null) {
				docView.goToPage(it.page);
				dialog.dismiss();
			} else {
				adapter.toggleAt(pos);   // a heading without a target just expands / collapses
			}
		});
		dialog.show();

		final int cur = adapter.visiblePositionOfCurrent();
		if (cur >= 0)
			list.post(() -> list.setSelectionFromTop(cur, dp(80)));
	}

	@Override
	protected void onPause() {
		rememberPosition();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		closeCore();
		super.onDestroy();
	}
}
