package com.whatsbb.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public class ThemeManager {

    public static final int BG = Color.parseColor("#0F0F10");
    public static final int HEADER_BG = Color.parseColor("#141416");
    public static final int SURFACE = Color.parseColor("#171719");
    public static final int INPUT_BG = Color.parseColor("#1C1C1F");
    public static final int TEXT_PRIMARY = Color.parseColor("#F2F2F2");
    public static final int TEXT_SECONDARY = Color.parseColor("#808086");
    public static final int TEXT_DIM = Color.parseColor("#606066");
    public static final int DIVIDER = Color.parseColor("#252528");
    public static final int BTN_BG = Color.parseColor("#1F1F23");
    public static final int BTN_BORDER = Color.parseColor("#323237");

    public static GradientDrawable createBox(int bgColor, int borderColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        if (borderColor != 0) {
            gd.setStroke(1, borderColor);
        }
        if (radiusPx > 0) {
            gd.setCornerRadius(radiusPx);
        }
        return gd;
    }
}
