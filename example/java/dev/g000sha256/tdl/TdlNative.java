package dev.g000sha256.tdl;

public final class TdlNative {

    private TdlNative() {}

    static {
        System.loadLibrary("tdjni");
    }

    public static native int createClientId();

    public static native void send(int clientId, long eventId, TdApi.Function<?> function);

    public static native int receive(int[] clientIds, long[] eventIds, TdApi.Object[] events, double timeout);

    public static native TdApi.Object execute(TdApi.Function<?> function);

    public static native void setLogMessageHandler(int maxVerbosityLevel, LogMessageHandler logMessageHandler);

    public interface LogMessageHandler {

        void onLogMessage(int verbosityLevel, String message);

    }

}
