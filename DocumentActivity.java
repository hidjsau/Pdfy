package net.timelegend.chaka.viewer;

import com.artifex.mupdf.fitz.SeekableInputStream;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
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

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		Tool.create(this);

		picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
			if (uri != null)
				openUri(uri);
		});

		showStartScreen();

		Intent intent = getIntent();
		if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
			openUri(intent.getData());
		}
		else if (savedInstanceState == null) {
			pickerShown = true;
			picker.launch(new String[] { PDF_MIME });
		}
	}

	private void showStartScreen() {
		FrameLayout root = new FrameLayout(this);
		Button b = new Button(this);
		b.setText(R.string.open_pdf);
		b.setOnClickListener(v -> picker.launch(new String[] { PDF_MIME }));
		root.addView(b, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER));
		setContentView(root);
	}

	private void closeCore() {
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
		closeCore();
		try {
			core = openCore(uri);
		}
		catch (Throwable x) {
			core = null;
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
		docView = new ReaderView(this);
		docView.setAdapter(new PageAdapter(this, core));
		getOnBackPressedDispatcher().addCallback(this, docView.getBackCallback());
		setContentView(docView);
	}

	@Override
	protected void onDestroy() {
		closeCore();
		super.onDestroy();
	}
}
