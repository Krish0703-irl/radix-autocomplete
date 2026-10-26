import java.util.Locale;

/**
 * Input rules for words and prefixes, shared by every dictionary.
 *
 * A word is normalised to lower case (so "Car" and "car" are the same word) and
 * may contain letters (any language), digits, apostrophes and hyphens. Anything
 * else is rejected with a message that says exactly what is wrong, so the demo
 * never crashes on bad input.
 *
 * Owner: Member 1 (input layer + baselines)
 */
public final class Words {

    public static final int MAX_LENGTH = 64;

    private Words() { }

    /** Normalises and validates a word. Throws IllegalArgumentException if it is not allowed. */
    public static String word(String raw) {
        String w = clean(raw, "word");
        if (w.isEmpty()) throw new IllegalArgumentException("word is empty");
        return w;
    }

    /** Normalises and validates a prefix. The empty prefix is allowed and means "every word". */
    public static String prefix(String raw) {
        return clean(raw == null ? "" : raw, "prefix");
    }

    private static String clean(String raw, String what) {
        if (raw == null) throw new IllegalArgumentException(what + " is missing");
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(what + " is longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                throw new IllegalArgumentException(what + " '" + raw.trim() + "' contains a space; give one word");
            }
            if (!Character.isLetterOrDigit(c) && c != '\'' && c != '-') {
                throw new IllegalArgumentException(what + " '" + raw.trim() + "' contains '" + c
                        + "'; only letters, digits, ' and - are allowed");
            }
        }
        return s;
    }

    /** Parses a frequency: a whole number from 0 up, optionally with k (thousand) or m (million): 1.5k, 2m. */
    public static long frequency(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("-")) throw new IllegalArgumentException("frequency " + raw.trim() + " is negative");
        long mult = 1;
        if (s.endsWith("k")) { mult = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1_000_000; s = s.substring(0, s.length() - 1); }
        if (!s.matches("\\d{1,12}(\\.\\d{1,6})?")) {
            throw new IllegalArgumentException("frequency '" + raw.trim() + "' is not a number like 250, 1.5k or 2m");
        }
        double v = Double.parseDouble(s) * mult;
        if (v != Math.rint(v)) throw new IllegalArgumentException("frequency '" + raw.trim() + "' is not a whole number");
        return (long) v;
    }

    /** Parses k (how many suggestions): 1 to 1000. */
    public static int count(String raw, String what) {
        try {
            int k = Integer.parseInt(raw.trim());
            if (k < 1 || k > 1000) throw new IllegalArgumentException(what + " must be between 1 and 1000");
            return k;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " '" + raw.trim() + "' is not a whole number");
        }
    }
}
