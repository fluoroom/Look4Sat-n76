package com.dw.sbc;

/**
 * JNI wrapper for libsbc.so — must stay in package com.dw.sbc so
 * the native symbol names (Java_com_dw_sbc_SbcDecoder_*) match.
 *
 * b(in, offset, length, out) → number of PCM bytes written to out (big-endian 16-bit PCM).
 */
public class SbcDecoder {

    private long handle;

    static {
        System.loadLibrary("sbc");
    }

    public SbcDecoder(boolean bigEndian) {
        handle = native_setup(0L, bigEndian);
        if (handle == 0) throw new OutOfMemoryError("SbcDecoder native_setup failed");
    }

    public int b(byte[] in, int offset, int length, byte[] out) {
        if (handle == 0) throw new IllegalStateException("released");
        long r = native_decode(handle, in, offset, length, out);
        return (int) r;
    }

    public void e() {
        long h = handle;
        handle = 0;
        if (h != 0) native_release(h);
    }

    @Override
    protected void finalize() throws Throwable {
        try { e(); } finally { super.finalize(); }
    }

    private native long native_setup(long j, boolean bigEndian);
    private native long native_decode(long handle, byte[] in, int offset, int length, byte[] out);
    private native void native_release(long handle);
    private static native byte[] native_static_decode(byte[] in, boolean bigEndian);
}
