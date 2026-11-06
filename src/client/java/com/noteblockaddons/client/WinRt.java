package com.noteblockaddons.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Bare-bones Windows Runtime calls through Java's built-in foreign function API (no JNA, no
 * PowerShell, no extra process). A WinRT object is a pointer to a pointer to a table of functions;
 * every method returns an HRESULT and hands its result back through an out-pointer.
 *
 * Slot numbers count from the start of that table. Every WinRT interface starts with the six
 * IInspectable slots (0-2 IUnknown, 3-5 IInspectable), so an interface's first own method is slot 6.
 * Layouts and IIDs are taken from Microsoft's generated windows-rs bindings, not from memory:
 * a wrong slot here doesn't throw, it calls a random function and takes the game down with it.
 */
final class WinRt {
    static final int SLOT_QUERY_INTERFACE = 0;
    static final int SLOT_RELEASE = 2;
    static final int FIRST_SLOT = 6;

    // IAsyncInfo / IAsyncOperation<T>
    private static final MemorySegment IID_IASYNC_INFO = guid("00000036-0000-0000-c000-000000000046");
    private static final int ASYNC_INFO_STATUS = 7;
    private static final int ASYNC_INFO_CANCEL = 9;
    private static final int ASYNC_OPERATION_GET_RESULTS = 8;
    private static final int ASYNC_STARTED = 0;
    private static final int ASYNC_COMPLETED = 1;

    // classic COM IStream, for reading picture bytes
    private static final MemorySegment IID_ISTREAM = guid("0000000c-0000-0000-c000-000000000046");
    private static final int ISTREAM_READ = 3;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final FunctionDescriptor GETTER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor NO_ARGS = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor QUERY = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor READ = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    // the function pointer goes in as the first argument, so one handle serves every method with that shape
    private static final MethodHandle CALL_GETTER = LINKER.downcallHandle(GETTER);
    private static final MethodHandle CALL_NO_ARGS = LINKER.downcallHandle(NO_ARGS);
    private static final MethodHandle CALL_QUERY = LINKER.downcallHandle(QUERY);
    private static final MethodHandle CALL_READ = LINKER.downcallHandle(READ);

    /** combase/shcore functions, loaded on first use (keeps the vtable plumbing usable without them). */
    private static final class Lib {
        static final MethodHandle RO_INITIALIZE;
        static final MethodHandle RO_GET_ACTIVATION_FACTORY;
        static final MethodHandle WINDOWS_CREATE_STRING;
        static final MethodHandle WINDOWS_DELETE_STRING;
        static final MethodHandle WINDOWS_GET_STRING_RAW_BUFFER;
        static final MethodHandle CREATE_STREAM_OVER_RANDOM_ACCESS_STREAM;

        static {
            SymbolLookup combase = SymbolLookup.libraryLookup("combase", Arena.global());
            SymbolLookup shcore = SymbolLookup.libraryLookup("shcore", Arena.global());
            RO_INITIALIZE = function(combase, "RoInitialize", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            RO_GET_ACTIVATION_FACTORY = function(combase, "RoGetActivationFactory", QUERY);
            WINDOWS_CREATE_STRING = function(combase, "WindowsCreateString", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            WINDOWS_DELETE_STRING = function(combase, "WindowsDeleteString", NO_ARGS);
            WINDOWS_GET_STRING_RAW_BUFFER = function(combase, "WindowsGetStringRawBuffer", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            CREATE_STREAM_OVER_RANDOM_ACCESS_STREAM = function(shcore, "CreateStreamOverRandomAccessStream", QUERY);
        }
    }

    private WinRt() {}

    private static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        MemorySegment address = library.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing " + name));
        return LINKER.downcallHandle(address, descriptor);
    }

    /** Joins the multithreaded apartment on the calling thread. "Already joined" is fine too. */
    static void initThread() {
        int hr = (int) invoke(Lib.RO_INITIALIZE, 1);
        // S_OK, S_FALSE (already initialized) and RPC_E_CHANGED_MODE (thread already STA) all work for us
        if (hr < 0 && hr != 0x80010106) throw new WinRtException("RoInitialize", hr);
    }

    /** The static side of a runtime class, e.g. GlobalSystemMediaTransportControlsSessionManager.RequestAsync. */
    static MemorySegment activationFactory(Arena arena, String className, MemorySegment iid) {
        MemorySegment name = createString(arena, className);
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            check("RoGetActivationFactory(" + className + ")", (int) invoke(Lib.RO_GET_ACTIVATION_FACTORY, name, iid, out));
            return out.get(ADDRESS, 0);
        } finally {
            invoke(Lib.WINDOWS_DELETE_STRING, name);
        }
    }

    /** Calls a method that returns an object; the caller must {@link #release} the result. NULL when there is none. */
    static MemorySegment object(Arena arena, MemorySegment self, int slot, String what) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(what, getter(self, slot, out));
        return out.get(ADDRESS, 0);
    }

    static String string(Arena arena, MemorySegment self, int slot, String what) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(what, getter(self, slot, out));
        MemorySegment handle = out.get(ADDRESS, 0);
        if (handle.equals(MemorySegment.NULL)) return ""; // a NULL HSTRING is the empty string
        try {
            MemorySegment length = arena.allocate(JAVA_INT);
            MemorySegment chars = (MemorySegment) invoke(Lib.WINDOWS_GET_STRING_RAW_BUFFER, handle, length);
            int count = length.get(JAVA_INT, 0);
            return new String(chars.reinterpret(count * 2L).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
        } finally {
            invoke(Lib.WINDOWS_DELETE_STRING, handle);
        }
    }

    static int int32(Arena arena, MemorySegment self, int slot, String what) {
        MemorySegment out = arena.allocate(JAVA_INT);
        check(what, getter(self, slot, out));
        return out.get(JAVA_INT, 0);
    }

    /** TimeSpan and DateTime are both a single 64-bit count of 100ns ticks. */
    static long int64(Arena arena, MemorySegment self, int slot, String what) {
        MemorySegment out = arena.allocate(JAVA_LONG);
        check(what, getter(self, slot, out));
        return out.get(JAVA_LONG, 0);
    }

    /**
     * Calls a method that returns IAsyncOperation&lt;T&gt; and waits for its result by polling.
     * Returns NULL if it doesn't finish in time, fails, or has no result.
     */
    static MemorySegment await(Arena arena, MemorySegment self, int slot, String what, long timeoutMs) {
        MemorySegment operation = object(arena, self, slot, what);
        if (operation.equals(MemorySegment.NULL)) return MemorySegment.NULL;
        MemorySegment info = MemorySegment.NULL;
        try {
            info = queryInterface(arena, operation, IID_IASYNC_INFO);
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            int status;
            while ((status = int32(arena, info, ASYNC_INFO_STATUS, what + " status")) == ASYNC_STARTED) {
                if (System.nanoTime() > deadline) {
                    invoke(CALL_NO_ARGS, method(info, ASYNC_INFO_CANCEL), info);
                    return MemorySegment.NULL;
                }
                sleep(5);
            }
            if (status != ASYNC_COMPLETED) return MemorySegment.NULL;
            return object(arena, operation, ASYNC_OPERATION_GET_RESULTS, what + " result");
        } finally {
            release(info);
            release(operation);
        }
    }

    /** Reads a whole Windows.Storage.Streams random-access stream into memory. */
    static byte[] readAll(Arena arena, MemorySegment randomAccessStream) {
        MemorySegment stream = arena.allocate(ADDRESS);
        check("CreateStreamOverRandomAccessStream",
                (int) invoke(Lib.CREATE_STREAM_OVER_RANDOM_ACCESS_STREAM, randomAccessStream, IID_ISTREAM, stream));
        MemorySegment istream = stream.get(ADDRESS, 0);
        try {
            MemorySegment chunk = arena.allocate(64 * 1024);
            MemorySegment read = arena.allocate(JAVA_INT);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            while (true) {
                int hr = (int) invoke(CALL_READ, method(istream, ISTREAM_READ), istream, chunk, (int) chunk.byteSize(), read);
                check("IStream.Read", hr);
                int n = read.get(JAVA_INT, 0);
                if (n <= 0) break;
                bytes.write(chunk.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), 0, n);
                if (hr == 1) break; // S_FALSE: hit the end
            }
            return bytes.toByteArray();
        } finally {
            release(istream);
        }
    }

    static MemorySegment queryInterface(Arena arena, MemorySegment self, MemorySegment iid) {
        MemorySegment out = arena.allocate(ADDRESS);
        check("QueryInterface", (int) invoke(CALL_QUERY, method(self, SLOT_QUERY_INTERFACE), self, iid, out));
        return out.get(ADDRESS, 0);
    }

    static void release(MemorySegment self) {
        if (self != null && !self.equals(MemorySegment.NULL)) invoke(CALL_NO_ARGS, method(self, SLOT_RELEASE), self);
    }

    /** A GUID in its in-memory layout: three little-endian fields, then eight bytes as written. */
    static MemorySegment guid(String text) {
        String hex = text.replace("-", "");
        MemorySegment guid = Arena.global().allocate(16);
        guid.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            guid.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + i * 2, 18 + i * 2), 16));
        }
        return guid;
    }

    private static int getter(MemorySegment self, int slot, MemorySegment out) {
        return (int) invoke(CALL_GETTER, method(self, slot), self, out);
    }

    /** Looks up slot {@code slot} in the object's function table. */
    private static MemorySegment method(MemorySegment self, int slot) {
        if (self.equals(MemorySegment.NULL)) throw new WinRtException("call on a NULL object", 0x80004003);
        MemorySegment table = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return table.reinterpret(ADDRESS.byteSize() * (slot + 1L)).getAtIndex(ADDRESS, slot);
    }

    private static MemorySegment createString(Arena arena, String text) {
        MemorySegment chars = arena.allocateFrom(text, StandardCharsets.UTF_16LE);
        MemorySegment out = arena.allocate(ADDRESS);
        check("WindowsCreateString", (int) invoke(Lib.WINDOWS_CREATE_STRING, chars, text.length(), out));
        return out.get(ADDRESS, 0);
    }

    private static void check(String what, int hr) {
        if (hr < 0) throw new WinRtException(what, hr);
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WinRtException("interrupted", 0x80004004);
        }
    }

    static final class WinRtException extends RuntimeException {
        WinRtException(String what, int hr) {
            super(what + " failed (HRESULT 0x" + Integer.toHexString(hr).toUpperCase() + ")");
        }
    }
}
