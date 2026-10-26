import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Interactive command line for the live demo.
 *
 * Every command runs inside try/catch: bad input prints the reason and the
 * program keeps running - nothing the examiner types can crash it.
 *
 * Run with no arguments for the prompt, or pass one command to run it and exit.
 *
 * Owner: Member 3 (engine layer)
 */
public class Main {

    private final AutocompleteEngine engine = new AutocompleteEngine();

    public static void main(String[] args) throws IOException {
        Main m = new Main();
        if (args.length > 0) {
            m.execute(String.join(" ", args));
            return;
        }
        System.out.println("=====================================================================");
        System.out.println(" Radix Tree Based Autocomplete and Prefix Search System");
        System.out.println("=====================================================================");
        m.printHelp();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("\nac> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) break;
            if (!m.execute(line)) break;
        }
        System.out.println("bye");
    }

    boolean execute(String line) {
        String[] w = line.trim().split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) return true;
        try {
            switch (w[0].toLowerCase()) {
                case "add":      cmdAdd(w); break;
                case "del":
                case "delete":   cmdDel(w); break;
                case "search":
                case "find":     cmdSearch(w); break;
                case "complete":
                case "c":        cmdComplete(w); break;
                case "prefix":   cmdPrefix(w); break;
                case "count":    cmdCount(w); break;
                case "select":   cmdSelect(w); break;
                case "history":  cmdHistory(); break;
                case "trace":    cmdTrace(w); break;
                case "unique":   cmdUnique(w); break;
                case "tree":     cmdTree(w); break;
                case "stats":    cmdStats(); break;
                case "sample":   cmdSample(); break;
                case "words":    cmdLoad(new String[] {"load", AutocompleteEngine.WORD_FILE}); break;
                case "load":     cmdLoad(w); break;
                case "clear":    engine.tree().clear(); System.out.println("dictionary cleared"); break;
                case "test":     SelfTest.run(System.out::println); break;
                case "bench":
                    if (w.length > 1 && w[1].equalsIgnoreCase("quick")) Benchmark.runQuick(System.out::println);
                    else Benchmark.run(System.out::println);
                    break;
                case "help":
                case "?":        printHelp(); break;
                case "exit":
                case "quit":     return false;
                default:         System.out.println("unknown command '" + w[0] + "' - type help");
            }
        } catch (IllegalArgumentException e) {
            System.out.println("error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("unexpected error: " + e);
        }
        return true;
    }

    private void printHelp() {
        System.out.println();
        System.out.println(" add <word> [freq]          add a word, or set its frequency (default 1)");
        System.out.println(" del <word>                 delete a word");
        System.out.println(" search <word>              is the word stored? with what frequency?");
        System.out.println(" complete <prefix> [k]      top-k suggestions by frequency (default k = 5); short: c");
        System.out.println(" prefix <prefix> [n]        all words with the prefix, alphabetical (default 20)");
        System.out.println(" count <prefix>             how many words start with the prefix");
        System.out.println(" select <word>              the user picked this suggestion: frequency + 1");
        System.out.println(" history                    the last 10 selected words");
        System.out.println(" trace <prefix>             show how the prefix is found edge by edge");
        System.out.println(" unique <word>              shortest prefix that identifies the word");
        System.out.println(" tree [prefix]              draw the tree (or the part under a prefix)");
        System.out.println(" stats                      words, nodes, height, characters stored");
        System.out.println(" sample | words             load 25 demo words | the 50,000-word English list");
        System.out.println(" load <file>                load 'word [freq]' lines from a file");
        System.out.println(" test | bench [quick]       run the tests | the performance benchmark");
        System.out.println(" clear | help | exit");
    }

    private static void need(String[] w, int min, String usage) {
        if (w.length < min) throw new IllegalArgumentException("usage: " + usage);
    }

    private static void most(String[] w, int max, String usage) {
        if (w.length > max) throw new IllegalArgumentException("usage: " + usage + " (one word only)");
    }

    private void cmdAdd(String[] w) {
        need(w, 2, "add <word> [freq]");
        most(w, 3, "add <word> [freq]");
        if (w.length == 3 && !w[2].matches("-?\\d+(\\.\\d+)?[kKmM]?")) {
            throw new IllegalArgumentException("'" + w[1] + " " + w[2] + "' looks like two words; add takes one word and an optional frequency");
        }
        long f = w.length == 3 ? Words.frequency(w[2]) : 1;
        boolean added = engine.add(w[1], f);
        System.out.println((added ? "added   " : "updated ") + Words.word(w[1]) + " (frequency " + f + ")");
    }

    private void cmdDel(String[] w) {
        need(w, 2, "del <word>");
        most(w, 2, "del <word>");
        String word = Words.word(w[1]);
        System.out.println(engine.delete(word) ? "deleted " + word : "'" + word + "' is not in the dictionary (nothing deleted)");
    }

    private void cmdSearch(String[] w) {
        need(w, 2, "search <word>");
        most(w, 2, "search <word>");
        String word = Words.word(w[1]);
        long f = engine.frequency(word);
        if (f >= 0) {
            System.out.println("found   " + word + " (frequency " + f + ")");
            return;
        }
        int under = engine.tree().countPrefix(word);
        System.out.println("'" + word + "' is not a stored word"
                + (under > 0 ? ", but " + under + " stored word(s) start with it - try: complete " + word : ""));
    }

    private void cmdComplete(String[] w) {
        need(w, 2, "complete <prefix> [k]");
        most(w, 3, "complete <prefix> [k]");
        int k = w.length == 3 ? Words.count(w[2], "k") : 5;
        String prefix = Words.prefix(w[1]);
        List<Suggestion> s = engine.complete(prefix, k);
        if (s.isEmpty()) {
            String known = engine.knownPart(prefix);
            System.out.println("no words start with '" + prefix + "'"
                    + (known.isEmpty() ? "" : "; the longest part that matches is '" + known + "' - try: complete " + known));
            return;
        }
        int total = engine.tree().countPrefix(prefix);
        System.out.println("top " + s.size() + " of " + total + " words starting with '" + prefix + "':");
        for (int i = 0; i < s.size(); i++) System.out.printf("  %2d. %-24s %,d%n", i + 1, s.get(i).word(), s.get(i).frequency());
        System.out.println("  (" + engine.tree().lastExpanded() + " nodes expanded to find them)");
    }

    private void cmdPrefix(String[] w) {
        need(w, 2, "prefix <prefix> [n]");
        most(w, 3, "prefix <prefix> [n]");
        int n = w.length == 3 ? Words.count(w[2], "n") : 20;
        String prefix = Words.prefix(w[1]);
        List<Suggestion> s = engine.tree().listPrefix(prefix, n);
        int total = engine.tree().countPrefix(prefix);
        System.out.println(total + " word(s) start with '" + prefix + "'" + (total > s.size() ? ", first " + s.size() + " alphabetically:" : ":"));
        for (Suggestion x : s) System.out.println("  " + x);
    }

    private void cmdCount(String[] w) {
        need(w, 2, "count <prefix>");
        most(w, 2, "count <prefix>");
        String prefix = Words.prefix(w[1]);
        System.out.println(engine.tree().countPrefix(prefix) + " word(s) start with '" + prefix + "'");
    }

    private void cmdHistory() {
        List<String> h = engine.history();
        System.out.println(h.isEmpty() ? "no words selected yet" : "last selected (newest first): " + String.join(", ", h));
    }

    private void cmdSelect(String[] w) {
        need(w, 2, "select <word>");
        most(w, 2, "select <word>");
        long f = engine.select(w[1]);
        System.out.println(f < 0 ? "'" + Words.word(w[1]) + "' is not in the dictionary"
                                 : Words.word(w[1]) + " selected: frequency is now " + f);
    }

    private void cmdUnique(String[] w) {
        need(w, 2, "unique <word>");
        most(w, 2, "unique <word>");
        String word = Words.word(w[1]);
        String u = engine.tree().shortestUniquePrefix(word);
        if (u == null) System.out.println("'" + word + "' is not in the dictionary");
        else if (u.equals(word) && engine.tree().countPrefix(word) > 1) System.out.println("no shorter prefix: other words start with '" + word + "'");
        else System.out.println("'" + u + "' is enough to identify '" + word + "'");
    }

    private void cmdTrace(String[] w) {
        need(w, 2, "trace <prefix>");
        most(w, 2, "trace <prefix>");
        String prefix = Words.prefix(w[1]);
        System.out.println("finding '" + prefix + "':");
        engine.tree().trace(prefix).forEach(System.out::println);
    }

    private void cmdTree(String[] w) {
        most(w, 2, "tree [prefix]");
        String prefix = w.length == 2 ? Words.prefix(w[1]) : "";
        System.out.println("(edge label, * = a word ends here, [words in subtree, best frequency in subtree])");
        System.out.print(engine.tree().render(prefix, 60));
    }

    private void cmdStats() {
        RadixTree t = engine.tree();
        System.out.printf("words %,d   nodes %,d   height %d   characters on edges %,d%n",
                t.size(), t.nodeCount(), t.height(), t.labelChars());
        System.out.println("nodes <= 2N always holds (every non-root node ends a word or has 2+ children)");
    }

    private void cmdSample() {
        int added = 0;
        for (Object[] x : AutocompleteEngine.sampleWords()) if (engine.add((String) x[0], (Long) x[1])) added++;
        System.out.println("loaded " + added + " sample words - try: tree, complete ca, complete d 3, prefix car, trace care");
    }

    private void cmdLoad(String[] w) {
        need(w, 2, "load <file>");
        Path p = Path.of(w[1]);
        if (!Files.isRegularFile(p)) {
            System.out.println("error: file '" + w[1] + "' not found (run the program from the project folder)");
            return;
        }
        try {
            long t0 = System.nanoTime();
            AutocompleteEngine.LoadResult r = engine.load(p);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("added " + r.added + ", updated " + r.updated + ", rejected " + r.errors.size() + " (" + ms + " ms)");
            r.errors.stream().limit(10).forEach(e -> System.out.println("  " + e));
            if (r.errors.size() > 10) System.out.println("  ... " + (r.errors.size() - 10) + " more");
        } catch (IOException e) {
            System.out.println("error: cannot read '" + w[1] + "' (" + e.getClass().getSimpleName() + ")");
        }
    }
}
