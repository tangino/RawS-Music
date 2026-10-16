package com.whl.quickjs.wrapper;

import java.util.ArrayList;
import java.util.HashMap;

/* JADX INFO: loaded from: classes.dex */
public class QuickJSFunction extends QuickJSObject implements JSFunction {
    private Status currentStatus;
    private int stashTimes;
    private final long thisPointer;

    public enum Status {
        NOT_CALLED,
        CALLING,
        CALLED
    }

    public QuickJSFunction(QuickJSContext quickJSContext, long j4, long j5) {
        super(quickJSContext, j4);
        this.stashTimes = 0;
        this.currentStatus = Status.NOT_CALLED;
        this.thisPointer = j5;
    }

    @Override // com.whl.quickjs.wrapper.JSFunction
    public Object call(Object... objArr) {
        checkRefCountIsZero();
        this.currentStatus = Status.CALLING;
        Object objCall = getContext().call(this, this.thisPointer, objArr);
        this.currentStatus = Status.CALLED;
        if (this.stashTimes > 0) {
            for (int i4 = 0; i4 < this.stashTimes; i4++) {
                release();
            }
            this.stashTimes = 0;
        }
        return objCall;
    }

    @Override // com.whl.quickjs.wrapper.JSFunction
    public void callVoid(Object... objArr) {
        Object objCall = call(objArr);
        if (objCall instanceof JSObject) {
            ((JSObject) objCall).release();
        }
    }

    @Override // com.whl.quickjs.wrapper.QuickJSObject, com.whl.quickjs.wrapper.JSObject
    public void release() {
        if (this.currentStatus == Status.CALLING) {
            this.stashTimes++;
        } else {
            super.release();
        }
    }

    @Override // com.whl.quickjs.wrapper.QuickJSObject, com.whl.quickjs.wrapper.JSObject
    public ArrayList<Object> toArray() {
        throw new UnsupportedOperationException("JSFunction types do not support conversion to map or array.");
    }

    @Override // com.whl.quickjs.wrapper.QuickJSObject, com.whl.quickjs.wrapper.JSObject
    public HashMap<String, Object> toMap() {
        throw new UnsupportedOperationException("JSFunction types do not support conversion to map or array.");
    }
}
