package jcvm.api;

import jcvm.rt.VM;

/**
 * A natively implemented API method. Results are reported through
 * {@code vm.retShort/retInt/retRef}; a void method simply returns.
 */
public interface NativeImpl {

    void invoke(VM vm, NativeArgs args);
}
