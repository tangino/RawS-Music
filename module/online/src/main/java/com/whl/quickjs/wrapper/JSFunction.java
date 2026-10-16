package com.whl.quickjs.wrapper;

/* JADX INFO: loaded from: classes.dex */
public interface JSFunction extends JSObject {
    Object call(Object... objArr);

    void callVoid(Object... objArr);
}
