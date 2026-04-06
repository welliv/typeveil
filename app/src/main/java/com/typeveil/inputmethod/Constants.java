package com.typeveil.inputmethod;

/**
 * Key codes — negative values are functional (non-character) keys.
 * Standard codes mirror LatinIME conventions.
 * TypeVeil additions: -100 (Veil), -101 (Unveil), -102 (Toggle).
 */
public final class Constants {
    private Constants() {}

    public static final int CODE_SHIFT = -1;
    public static final int CODE_SWITCH_ALPHA_SYMBOL = -2;
    public static final int CODE_SWITCH_SYMBOL = -3;
    public static final int CODE_OUTPUT_TEXT = -4;
    public static final int CODE_DELETE = -5;
    public static final int CODE_SETTINGS = -6;
    public static final int CODE_SHORTCUT = -7;
    public static final int CODE_ACTION_NEXT = -8;
    public static final int CODE_ACTION_PREVIOUS = -9;

    // TypeVeil codes
    public static final int CODE_VEIL_ENCRYPT = -100;
    public static final int CODE_VEIL_DECRYPT = -101;
    public static final int CODE_VEIL_TOGGLE = -102;

    public static final int KEYCODE_SPACE = 32;
    public static final int KEYCODE_ENTER_NEWLINE = 10;
    public static final int KEYCODE_ENTER_ACTION = -4;
}
