package com.github.unidbg.linux.android.dvm;

import java.util.Arrays;

public final class CallbackArguments extends VarArg {
    public CallbackArguments(BaseVM vm, String owner, String name, String descriptor, Object... values) {
        super(vm, new DvmMethod(vm.resolveClass(owner), name, descriptor, false));
        args.addAll(Arrays.asList(values));
    }
}
