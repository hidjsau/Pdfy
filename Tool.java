package net.timelegend.chaka.viewer;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

public class Tool
{
    public final static String TAG = "ChakaPdf";
    public static Context mContext;

    // text selection colors
    public static final int SELECTION_COLOR = 0x8090EE90;
    public static final int HANDLE_COLOR = 0xFF3CB371;

    public static int LEFT_HANDLE = 1;
    public static int RIGHT_HANDLE = 2;
    public static int BOTH_HANDLE = 3;
    public static int NO_HANDLE = 0;
    public static int HANDLE_EXTEND = 1;
    public static int HANDLE_NO_EXTEND = 0;

    public static void create(Context context) {
        mContext = context;
    }

    public final static <T> void i(T v) {
        Log.i(TAG, String.valueOf(v));
    }

    public final static <T> void e(T v) {
        Log.e(TAG, String.valueOf(v));
    }

    public static String getResourceString(int id) {
        return mContext.getResources().getString(id);
    }

    public static <T> void toast(T t) {
        Toast.makeText(mContext, String.valueOf(t), Toast.LENGTH_SHORT).show();
    }
}
