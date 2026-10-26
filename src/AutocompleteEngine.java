import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The autocomplete service the demo talks to. It validates every input (Words),
 * keeps the dictionary in a radix tree, and adds the features a search box
 * needs on top of the data structure: learning from what users pick, loading
 * word lists from files, and a fallback when nothing matches.
 *
 * Owner: Member 3 (engine layer)
 */
public class AutocompleteEngine {

    /** The 50,000-word English list (relative to the project folder). */
    public static String WORD_FILE = "data/words_50k.txt";

    private final RadixTree tree = new RadixTree();

    public RadixTree tree() { return tree; }

    public boolean add(String rawWord, long freq) {
        return tree.insert(Words.word(rawWord), freq);
    }

    public boolean delete(String rawWord) {
        return tree.delete(Words.word(rawWord));
    }

    public long frequency(String rawWord) {
        return tree.frequency(Words.word(rawWord));
    }

    /** Top-k completions. */
    public List<Suggestion> complete(String rawPrefix, int k) {
        return tree.topK(Words.prefix(rawPrefix), k);
    }

    /**
     * The user picked `word` from the suggestions: it becomes more likely to be
     * suggested next time (frequency + 1). Returns the new frequency, or -1 if unknown.
     */
    public long select(String rawWord) {
        return tree.boost(Words.word(rawWord), 1);
    }

    /** When nothing starts with the prefix: the longest part of it that does match, or "" */
    public String knownPart(String rawPrefix) {
        String p = Words.prefix(rawPrefix);
        return p.substring(0, tree.longestKnownPrefix(p));
    }

    // ------------------------------------------------------------------ loading

    /** Result of loading a word file. */
    public static final class LoadResult {
        public int added, updated;
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * Loads words from a text file, one per line:   word [frequency]   (# starts a comment).
     * A missing frequency counts as 1. Bad lines are reported and skipped.
     */
    public LoadResult load(Path file) throws IOException {
        LoadResult r = new LoadResult();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            String line;
            int no = 0;
            while ((line = in.readLine()) != null) {
                no++;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] f = line.split("\\s+");
                if (f.length > 2) { r.errors.add("line " + no + ": expected 'word [frequency]'"); continue; }
                try {
                    long freq = f.length == 2 ? Words.frequency(f[1]) : 1;
                    if (add(f[0], freq)) r.added++; else r.updated++;
                } catch (IllegalArgumentException e) {
                    r.errors.add("line " + no + ": " + e.getMessage());
                }
            }
        }
        return r;
    }

    /** A small built-in dictionary for the demo: shared prefixes, words inside words, ties. */
    public static Object[][] sampleWords() {
        return new Object[][] {
            {"car", 90L}, {"card", 40L}, {"care", 70L}, {"career", 55L}, {"careful", 35L},
            {"cart", 25L}, {"carton", 10L}, {"cat", 80L}, {"catalog", 30L}, {"catch", 60L},
            {"cattle", 15L}, {"do", 95L}, {"dog", 75L}, {"door", 50L}, {"dorm", 20L},
            {"dot", 20L}, {"data", 85L}, {"database", 65L}, {"date", 45L}, {"day", 88L},
            {"tree", 50L}, {"trie", 30L}, {"trip", 40L}, {"trick", 20L}, {"try", 60L},
        };
    }
}
