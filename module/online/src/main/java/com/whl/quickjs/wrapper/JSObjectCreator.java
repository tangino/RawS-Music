package com.whl.quickjs.wrapper;

/* JADX INFO: loaded from: classes.dex */
public interface JSObjectCreator {
    JSArray newArray(QuickJSContext quickJSContext, long j4);

    JSFunction newFunction(QuickJSContext quickJSContext, long j4, long j5);

    JSObject newObject(QuickJSContext quickJSContext, long j4);
}
