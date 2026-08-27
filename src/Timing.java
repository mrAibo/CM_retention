import java.util.Locale;

/** Lightweight monotonic timing helper for admin diagnostics. */
final class Timing {
    private Timing() { }

    static long start() {
        return System.nanoTime();
    }

    static long elapsed(long startedAt) {
        return System.nanoTime() - startedAt;
    }

    static String format(long nanos) {
        return String.format(Locale.ROOT, "%.3f sec", nanos / 1000000000.0d);
    }

    static String since(long startedAt) {
        return format(elapsed(startedAt));
    }
}
