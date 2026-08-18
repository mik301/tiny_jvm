package jcvm.rt;

import jcvm.api.ApiPackage;

/** Lets a package resolve its Import component entries at link time. */
public interface PackageResolver {

    /** A previously loaded CAP with this AID, or null. */
    LoadedPackage findLoaded(byte[] aid);

    /** A natively implemented API package with this AID, or null. */
    ApiPackage findApi(byte[] aid);

    /** Instance size in 16 bit cells contributed by a natively implemented class. */
    int instanceSizeOf(String internalClassName);
}
