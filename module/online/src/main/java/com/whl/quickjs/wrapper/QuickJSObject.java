package com.whl.quickjs.wrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/* JADX INFO: loaded from: classes.dex */
public class QuickJSObject implements JSObject {
    private final QuickJSContext context;
    private final long pointer;
    private int refCount;
    private Throwable stackTrace;

    public QuickJSObject(QuickJSContext quickJSContext, long j4) {
        this.context = quickJSContext;
        this.pointer = j4;
        this.refCount++;
    }



    private void setPropertyObject(String str, Object obj) {
        checkRefCountIsZero();
        this.context.setProperty(this, str, obj);
    }

    public final void checkRefCountIsZero() {
        if (isRefCountZero()) {
            throw new QuickJSException("The call threw an exception, the reference count of the current object has already reached zero.");
        }
    }

    public void convertToMap(Object obj, Object obj2, HashSet<Long> hashSet, MapFilter mapFilter, Object obj3) {
        String str;
        Object property;
        JSObject jSObject = (JSObject) obj;
        long pointer = jSObject.getPointer();
        if (hashSet.contains(Long.valueOf(pointer))) {
            return;
        }
        hashSet.add(Long.valueOf(pointer));
        boolean z4 = obj instanceof JSArray;
        JSArray names = z4 ? (JSArray) obj : jSObject.getNames();
        int length = names.length();
        for (int i4 = 0; i4 < length; i4++) {
            property = null;
            if (z4) {
                property = names.get(i4);
                str = null;
            } else {
                str = (String) names.get(i4);
                if (mapFilter == null || !mapFilter.shouldSkipKey(str, pointer, obj3)) {
                    property = jSObject.getProperty(str);
                }
            }
            Object obj4 = property;
            String str2 = str;
            if (obj4 instanceof JSFunction) {
                ((JSFunction) obj4).release();
            } else if (obj4 instanceof JSArray) {
                JSArray jSArray = (JSArray) obj4;
                ArrayList arrayList = new ArrayList(jSArray.length());
                convertToMap(obj4, arrayList, hashSet, mapFilter, obj3);
                if (!arrayList.isEmpty()) {
                    if (obj2 instanceof HashMap) {
                        ((HashMap) obj2).put(str2, arrayList);
                    } else if (obj2 instanceof ArrayList) {
                        ((ArrayList) obj2).add(arrayList);
                    }
                }
                jSArray.release();
            } else if (obj4 instanceof JSObject) {
                HashMap map = new HashMap();
                convertToMap(obj4, map, hashSet, mapFilter, obj3);
                if (!map.isEmpty()) {
                    if (obj2 instanceof HashMap) {
                        ((HashMap) obj2).put(str2, map);
                    } else if (obj2 instanceof ArrayList) {
                        ((ArrayList) obj2).add(map);
                    }
                }
                ((JSObject) obj4).release();
            } else if (obj2 instanceof HashMap) {
                ((HashMap) obj2).put(str2, obj4);
            } else if (obj2 instanceof ArrayList) {
                ((ArrayList) obj2).add(obj4);
            }
        }
        if (z4) {
            return;
        }
        names.release();
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void decrementRefCount() {
        checkRefCountIsZero();
        this.refCount--;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Boolean getBoolean(String str) {
        Object property = getProperty(str);
        if (property instanceof Boolean) {
            return (Boolean) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Boolean getBooleanProperty(String str) {
        return getBoolean(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public byte[] getBytes(String str) {
        Object property = getProperty(str);
        if (property instanceof byte[]) {
            return (byte[]) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public QuickJSContext getContext() {
        return this.context;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Double getDouble(String str) {
        Object property = getProperty(str);
        if (property instanceof Double) {
            return (Double) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Double getDoubleProperty(String str) {
        return getDouble(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Integer getIntProperty(String str) {
        return getInteger(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Integer getInteger(String str) {
        Object property = getProperty(str);
        if (property instanceof Integer) {
            return (Integer) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSArray getJSArray(String str) {
        Object property = getProperty(str);
        if (property instanceof JSArray) {
            return (JSArray) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSArray getJSArrayProperty(String str) {
        return getJSArray(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSFunction getJSFunction(String str) {
        Object property = getProperty(str);
        if (property instanceof JSFunction) {
            return (JSFunction) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSFunction getJSFunctionProperty(String str) {
        return getJSFunction(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSObject getJSObject(String str) {
        Object property = getProperty(str);
        if (property instanceof JSObject) {
            return (JSObject) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSObject getJSObjectProperty(String str) {
        return getJSObject(str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Long getLong(String str) {
        Object property = getProperty(str);
        if (property instanceof Long) {
            return (Long) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSArray getNames() {
        checkRefCountIsZero();
        return (JSArray) this.context.getOwnPropertyNames(this);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public JSArray getOwnPropertyNames() {
        return getNames();
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public long getPointer() {
        return this.pointer;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Object getProperty(String str) {
        checkRefCountIsZero();
        return this.context.getProperty(this, str);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public int getRefCount() {
        return this.refCount;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public Throwable getStackTrace() {
        return this.stackTrace;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public String getString(String str) {
        Object property = getProperty(str);
        if (property instanceof String) {
            return (String) property;
        }
        return null;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public String getStringProperty(String str) {
        return getString(str);
    }

    public int hashCode() {
        return Arrays.hashCode(new long[]{this.pointer});
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void hold() {
        checkRefCountIsZero();
        this.refCount++;
        this.context.hold(this);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public boolean isAlive() {
        return !isRefCountZero();
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public boolean isRefCountZero() {
        return this.refCount == 0;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void release() {
        if (isRefCountZero()) {
            return;
        }
        this.refCount--;
        this.context.freeValue(this);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, String str2) {
        setPropertyObject(str, str2);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setStackTrace(Throwable th) {
        this.stackTrace = th;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public String stringify() {
        checkRefCountIsZero();
        return this.context.stringify(this);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public ArrayList<Object> toArray() {
        return toArray(null);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public HashMap<String, Object> toMap() {
        return toMap(null);
    }

    public String toString() {
        checkRefCountIsZero();
        JSFunction jSFunction = getJSFunction("toString");
        String str = (String) jSFunction.call(new Object[0]);
        jSFunction.release();
        return str;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, int i4) {
        setPropertyObject(str, Integer.valueOf(i4));
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public ArrayList<Object> toArray(MapFilter mapFilter, Object obj) {
        throw new UnsupportedOperationException("Object types are not yet supported for conversion to array. You should use toMap.");
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public HashMap<String, Object> toMap(MapFilter mapFilter) {
        return toMap(mapFilter, null);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, long j4) {
        setPropertyObject(str, Long.valueOf(j4));
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public ArrayList<Object> toArray(MapFilter mapFilter) {
        return toArray(mapFilter, null);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public HashMap<String, Object> toMap(MapFilter mapFilter, Object obj) {
        HashMap<String, Object> map = new HashMap<>();
        HashSet<Long> hashSet = new HashSet<>();
        convertToMap(this, map, hashSet, mapFilter, obj);
        hashSet.clear();
        return map;
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, JSObject jSObject) {
        setPropertyObject(str, jSObject);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, boolean z4) {
        setPropertyObject(str, Boolean.valueOf(z4));
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, double d4) {
        setPropertyObject(str, Double.valueOf(d4));
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, byte[] bArr) {
        setPropertyObject(str, bArr);
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, JSCallFunction jSCallFunction) {
        setPropertyObject(str, jSCallFunction);
    }

    private static Object invokeJSMethod(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override // com.whl.quickjs.wrapper.JSObject
    public void setProperty(String str, Class<?> cls) {
        Object instance = null;
        try {
            instance = cls.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        final Object objNewInstance = instance;
        if (objNewInstance != null) {
            JSObject jSObjectCreateNewJSObject = this.context.createNewJSObject();
            for (final Method method : cls.getMethods()) {
                if (method.isAnnotationPresent(JSMethod.class)) {
                    jSObjectCreateNewJSObject.setProperty(method.getName(), (JSCallFunction) objArr -> invokeJSMethod(method, objNewInstance, objArr));
                }
            }
            setProperty(str, jSObjectCreateNewJSObject);
            jSObjectCreateNewJSObject.release();
            return;
        }
        throw new NullPointerException("The JavaObj cannot be null. An error occurred in newInstance!");
    }
}
