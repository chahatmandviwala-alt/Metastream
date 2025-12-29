package com.metastream.webviewer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

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

    public void setImeBlocked(boolean blocked) {
        imeBlocked = blocked;
    }

    public boolean isImeBlocked() {
        return imeBlocked;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (imeBlocked) {
            // Returning null prevents Android from showing the system IME
            return null;
        }
        return super.onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        if (imeBlocked) return false;
        return super.onCheckIsTextEditor();
    }
}
