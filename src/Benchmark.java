import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Performance analysis. Every structure is built from the same words, asked the
 * same queries, and must return exactly the same answers as the radix tree.
 *
 * Datasets: the real 50,000-word English list (and its 10k / 25k most frequent
 * words), plus generated pseudo-words to show how each structure scales to
 * 200,000 and 500,000 words.
 *
 * Run options are the constants below - edit them, no command-line arguments needed.
 *
 * Owner: Member 4 (analysis)
 */
public class Benchmark {

    // ======================= run options (edit these) =======================
    static int[] REAL_SIZES      = {10_000, 25_000, 50_000};
    static int[] SYNTHETIC_SIZES = {200_000, 500_000};
    static int QUERIES           = 5_000;      // per prefix length
    static int K                 = 10;         // suggestions per query
    static int SCAN_MAX_WORDS    = 50_000;     // HashMap full scan is skipped above this
    static long SEED             = 5;
    static long BUDGET_MS        = 1500;       // a slow structure stops a timing loop after this long
    static String CSV_FILE       = "results/benchmark.csv";
    // ========================================================================

    public static void main(String[] args) { run(System.out::println); }

    public static void run(Consumer<String> out) { run(out, REAL_SIZES, SYNTHETIC_SIZES); }

    public static void runQuick(Consumer<String> out) { run(out, new int[] {10_000, 50_000}, new int[0]); }

    private static final List<String> csv = new ArrayList<>();

    static void run(Consumer<String> out, int[] realSizes, int[] synthSizes) {
        csv.clear();
        csv.add("dataset,words,structure,build_ms,search_ns,top10_len1_ns,top10_len2_ns,top10_len3_ns,count_len2_ns,nodes,heap_mb");
        List<Suggestion> real = readWords(out);
        out.accept("Benchmark: top-" + K + " autocomplete, " + QUERIES + " queries per prefix length, seed " + SEED);
        out.accept("(prefixes are the first 1, 2 or 3 letters of randomly chosen stored words)");
        out.accept("");
        // warm-up pass on a small table, output discarded, so the JIT has compiled every structure
        List<String> keep = new ArrayList<>(csv);
        runOne("warm-up", synthetic(20_000), x -> { });
        csv.clear();
        csv.addAll(keep);
        if (real != null) {
            for (int n : realSizes) runOne("English", real.subList(0, Math.min(n, real.size())), out);
        }
        for (int n : synthSizes) runOne("Generated", synthetic(n), out);
        writeCsv(out);
    }

    private static void runOne(String dataset, List<Suggestion> words, Consumer<String> out) {
        int n = words.size();
        Random rnd = new Random(SEED + n);
        String[][] prefixes = new String[4][QUERIES];
        String[] exact = new String[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            exact[i] = words.get(rnd.nextInt(n)).word();
            for (int len = 1; len <= 3; len++) {
                String w = words.get(rnd.nextInt(n)).word();
                prefixes[len][i] = w.substring(0, Math.min(len, w.length()));
            }
        }

        out.accept(String.format("%s words, N = %,d", dataset, n));
        out.accept(String.format("  %-30s %9s %10s %12s %12s %12s %10s %10s %8s", "structure", "build ms", "search ns",
                "top10 p=1 ns", "top10 p=2 ns", "top10 p=3 ns", "count ns", "nodes", "heap MB"));

        RadixTree radix = new RadixTree();
        List<List<Suggestion>> expected = new ArrayList<>();
        measure(dataset, radix, words, exact, prefixes, expected, true, false, out);
        measure(dataset, radix, words, exact, prefixes, expected, false, true, out);   // same tree, no pruning
        measure(dataset, new Baselines.StandardTrie(), words, exact, prefixes, expected, false, false, out);
        measure(dataset, new Baselines.SortedTree(), words, exact, prefixes, expected, false, false, out);
        if (n <= SCAN_MAX_WORDS) measure(dataset, new Baselines.HashScan(), words, exact, prefixes, expected, false, false, out);

        // how much the cached `best` saves: nodes expanded vs words under the prefix
        long expanded = 0, under = 0;
        for (int i = 0; i < QUERIES; i++) {
            radix.topK(prefixes[1][i], K);
            expanded += radix.lastExpanded();
            under += radix.countPrefix(prefixes[1][i]);
        }
        out.accept(String.format("  1-letter prefixes: on average %,d words match, top-%d expanded only %.1f nodes",
                under / QUERIES, K, (double) expanded / QUERIES));
        for (int limit : new int[] {10, 100}) {               // alphabetical listing with a limit
            long t0 = System.nanoTime();
            long listed = 0;
            for (int i = 0; i < QUERIES; i++) listed += radix.listPrefix(prefixes[1][i], limit).size();
            out.accept(String.format("  listPrefix, first %d words of a 1-letter prefix: %.0f ns per query (%,d words listed)",
                    limit, (System.nanoTime() - t0) / (double) QUERIES, listed));
        }
        out.accept(String.format("  radix tree: %,d nodes for %,d words (%.2f per word), height %d, %,d edge characters",
                radix.nodeCount(), n, (double) radix.nodeCount() / n, radix.height(), radix.labelChars()));
        out.accept("  all structures returned identical results: YES");
        out.accept("");
    }

    /**
     * Builds the structure (unless reuse), then times exact search, top-k at prefix
     * lengths 1-3 and prefix counting. The first structure fills `expected`; every
     * later one must match it exactly.
     */
    private static void measure(String dataset, Dictionary d, List<Suggestion> words, String[] exact, String[][] prefixes,
                                List<List<Suggestion>> expected, boolean reference, boolean exhaustive, Consumer<String> out) {
        String name = exhaustive ? "Radix tree, no pruning" : d.name();
        long build = 0;
        double heapMb = 0;
        if (!exhaustive) {
            long before = usedHeap();
            long t0 = System.nanoTime();
            for (Suggestion s : words) d.insert(s.word(), s.frequency());
            build = (System.nanoTime() - t0) / 1_000_000;
            heapMb = Math.max(0, usedHeap() - before) / 1048576.0;
        }

        long sink = 0;
        // warm-up so the JIT has compiled the hot paths before timing
        for (int i = 0; i < Math.min(2000, exact.length); i++) {
            sink += d.frequency(exact[i]);
            sink += top(d, exhaustive, prefixes[2][i]).size();
        }

        long t = System.nanoTime();
        for (String w : exact) sink += d.frequency(w);
        double searchNs = (System.nanoTime() - t) / (double) exact.length;

        double[] topNs = new double[4];
        int idx = 0;
        for (int len = 1; len <= 3; len++) {
            String[] ps = prefixes[len];
            int count = 0;
            t = System.nanoTime();
            long stop = t + BUDGET_MS * 1_000_000;
            for (int i = 0; i < ps.length; i++, count++) {
                if ((i & 15) == 0 && i > 0 && System.nanoTime() > stop) break;      // slow structure: enough samples
                List<Suggestion> r = top(d, exhaustive, ps[i]);
                if (reference) expected.add(r);
                else if (!r.equals(expected.get(idx + i))) {
                    throw new IllegalStateException(name + " disagrees with the radix tree for '" + ps[i] + "'");
                }
                sink += r.size();
            }
            topNs[len] = (System.nanoTime() - t) / (double) count;
            idx += ps.length;
        }

        int cq = 0;
        t = System.nanoTime();
        long stop = t + BUDGET_MS * 1_000_000;
        for (int i = 0; i < prefixes[2].length; i++, cq++) {
            if ((i & 15) == 0 && i > 0 && System.nanoTime() > stop) break;
            sink += d.countPrefix(prefixes[2][i]);
        }
        double countNs = (System.nanoTime() - t) / (double) cq;
        if (sink == 42) System.out.print("");

        long nodes = d instanceof RadixTree ? ((RadixTree) d).nodeCount()
                   : d instanceof Baselines.StandardTrie ? ((Baselines.StandardTrie) d).nodeCount() : 0;
        out.accept(String.format("  %-30s %9s %10.0f %12.0f %12.0f %12.0f %10.0f %10s %8s", name,
                exhaustive ? "(same)" : String.valueOf(build), searchNs, topNs[1], topNs[2], topNs[3], countNs,
                nodes > 0 && !exhaustive ? String.format("%,d", nodes) : "-",
                exhaustive ? "-" : String.format("%.1f", heapMb)));
        csv.add(String.format("%s,%d,%s,%d,%.0f,%.0f,%.0f,%.0f,%.0f,%d,%.1f", dataset, words.size(), name, build, searchNs,
                topNs[1], topNs[2], topNs[3], countNs, nodes, heapMb));
    }

    private static List<Suggestion> top(Dictionary d, boolean exhaustive, String prefix) {
        return exhaustive ? ((RadixTree) d).topKExhaustive(prefix, K) : d.topK(prefix, K);
    }

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) System.gc();
        return r.totalMemory() - r.freeMemory();
    }

    /** Reads the word list, most frequent first. Returns null if the file is missing. */
    private static List<Suggestion> readWords(Consumer<String> out) {
        Path p = Path.of(AutocompleteEngine.WORD_FILE);
        if (!Files.isRegularFile(p)) {
            out.accept("note: " + p + " not found (run from the project folder); using generated words only");
            return null;
        }
        List<Suggestion> list = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(p)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.trim().split("\\s+");
                list.add(new Suggestion(Words.word(f[0]), f.length > 1 ? Words.frequency(f[1]) : 1));
            }
        } catch (IOException e) {
            out.accept("note: cannot read " + p + ": " + e.getMessage());
            return null;
        }
        list.sort(Suggestion.RANK);
        return list;
    }

    /** Pronounceable pseudo-words built from syllables, with Zipf-like frequencies. */
    static List<Suggestion> synthetic(int n) {
        String[] syl = {"ka", "ri", "to", "ma", "ne", "so", "lu", "pe", "da", "vi", "ro", "ti", "na", "be", "co",
                        "fa", "gi", "ho", "ju", "le", "mo", "pa", "qu", "se", "tu", "ve", "wa", "xi", "yo", "ze",
                        "st", "tr", "ch", "sh", "th", "ing", "er", "an", "on", "al"};
        Random rnd = new Random(SEED * 7 + n);
        Set<String> seen = new HashSet<>();
        List<Suggestion> out = new ArrayList<>(n);
        while (out.size() < n) {
            int parts = 2 + rnd.nextInt(4);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts; i++) sb.append(syl[rnd.nextInt(syl.length)]);
            String w = sb.toString();
            if (seen.add(w)) out.add(new Suggestion(w, 1 + (long) (1_000_000.0 / (out.size() + 1))));
        }
        return out;
    }

    private static void writeCsv(Consumer<String> out) {
        try {
            Path p = Path.of(CSV_FILE);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p))) {
                csv.forEach(w::println);
            }
            out.accept("Results saved to " + p.toAbsolutePath());
        } catch (IOException e) {
            out.accept("Could not write CSV: " + e.getMessage());
        }
    }
}
