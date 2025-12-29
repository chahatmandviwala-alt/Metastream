package com.metastream.webviewer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

/**
 * WebView variant that can hard-block the Android IME by presenting itself
 * as a non-text-editor and returning no InputConnection while blocked.
 *
 * Note: WebView/Chromium may still attempt to manage IME visibility internally;
 * this class is intended to be used together with web-layer suppression
 * (e.g., inputmode="none") when an in-page virtual keyboard is active.
 */
public class ImeBlockWebView extends WebView {

    private volatile boolean imeBlocked = false;

    public ImeBlockWebView(Context context) {
        super(context);
    }

    public ImeBlockWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ImeBlockWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * When blocked, WebView will not provide an InputConnection and will report
     * that it is not a text editor. This is a best-effort suppression layer.
     */
    public void setImeBlocked(boolean blocked) {
        imeBlocked = blocked;
    }

    public boolean isImeBlocked() {
        return imeBlocked;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (imeBlocked) {
            if (outAttrs != null) {
                // Defensive: make the EditorInfo as "non-editor" as possible.
                outAttrs.inputType = 0;
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
                outAttrs.actionLabel = null;
                outAttrs.actionId = 0;
                outAttrs.initialSelStart = 0;
                outAttrs.initialSelEnd = 0;
            }
            return null;
        }
        return super.onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return !imeBlocked && super.onCheckIsTextEditor();
    }
}
