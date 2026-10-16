package com.whl.quickjs.wrapper;

/* JADX INFO: loaded from: classes.dex */
public abstract class ModuleLoader {
    public abstract byte[] getModuleBytecode(String str);

    public abstract String getModuleStringCode(String str);

    public abstract boolean isBytecodeMode();

    public String moduleNormalizeName(String str, String str2) {
        return str2;
    }
}
