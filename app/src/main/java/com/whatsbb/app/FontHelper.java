package com.whatsbb.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class FontHelper {

    private static Typeface spaceGrotesk;

    public static Typeface getFont(Context ctx) {
        if (spaceGrotesk == null) {
            try {
                spaceGrotesk = Typeface.createFromAsset(ctx.getAssets(), "fonts/SpaceGrotesk-Regular.ttf");
            } catch (Exception e) {
                spaceGrotesk = Typeface.SANS_SERIF;
            }
        }
        return spaceGrotesk;
    }

    public static void apply(View view, Context ctx) {
        if (view == null) return;
        Typeface tf = getFont(ctx);
        applyRecursive(view, tf);
    }

    private static void applyRecursive(View v, Typeface tf) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            int style = Typeface.NORMAL;
            if (tv.getTypeface() != null) {
                style = tv.getTypeface().getStyle();
            }
            tv.setTypeface(tf, style);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyRecursive(vg.getChildAt(i), tf);
            }
        }
    }
}
