package com.whl.quickjs.wrapper;

/* JADX INFO: loaded from: classes.dex */
public class QuickJSException extends RuntimeException {
    private final boolean jsError;

    public QuickJSException(String str) {
        this(str, false);
    }

    public boolean isJSError() {
        return this.jsError;
    }

    public QuickJSException(String str, boolean z4) {
        super(str);
        this.jsError = z4;
    }
}
