package jcvm.jcre;

import jcvm.rt.BuiltinObject;
import jcvm.rt.JCObject;
import jcvm.rt.LoadedPackage;
import jcvm.util.Hex;

/** One registered applet instance. */
public final class AppletInstance {

    public final byte[] aid;
    public final JCObject object;
    public final LoadedPackage pkg;
    /** javacard.framework.AID view of this instance' AID. */
    public final BuiltinObject aidObject;

    public AppletInstance(byte[] aid, JCObject object, LoadedPackage pkg) {
        this.aid = aid;
        this.object = object;
        this.pkg = pkg;
        this.aidObject = new BuiltinObject("javacard/framework/AID");
        this.aidObject.data = aid;
    }

    public String aidHex() {
        return Hex.toHex(aid);
    }

    public String toString() {
        return aidHex() + "  (" + object.clazz.label + ")";
    }
}
