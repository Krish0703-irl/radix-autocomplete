import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Automated tests: input validation, the tree's shape after inserts and deletes,
 * ranking and tie-breaking, and a randomised cross-check against three other
 * structures with the tree's invariants verified after every single operation.
 *
 * Owner: Member 4 (analysis)
 */
public class SelfTest {

    // ======================= run options (edit these) =======================
    static int RANDOM_OPERATIONS = 20_000;
    static long SEED = 11;
    // ========================================================================

    private int passed, failed;
    private final Consumer<String> out;

    private SelfTest(Consumer<String> out) { this.out = out; }

    public static void main(String[] args) {
        System.exit(run(System.out::println) ? 0 : 1);
    }

    public static boolean run(Consumer<String> out) {
        SelfTest t = new SelfTest(out);
        t.validation();
        t.shape();
        t.deleting();
        t.ranking();
        t.prefixQueries();
        t.improvements();
        t.randomised();
        t.realWords();
        out.accept("");
        out.accept(String.format("RESULT: %d passed, %d failed", t.passed, t.failed));
        return t.failed == 0;
    }

    private void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        out.accept((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    private void rejects(String what, Runnable r) {
        try {
            r.run();
            check("rejects " + what, false);
        } catch (IllegalArgumentException e) {
            check("rejects " + what + "  [" + e.getMessage() + "]", true);
        }
    }

    private static String words(List<Suggestion> s) {
        StringBuilder sb = new StringBuilder();
        for (Suggestion x : s) sb.append(sb.length() == 0 ? "" : ",").append(x.word());
        return sb.toString();
    }

    private static AutocompleteEngine sample() {
        AutocompleteEngine e = new AutocompleteEngine();
        for (Object[] x : AutocompleteEngine.sampleWords()) e.add((String) x[0], (Long) x[1]);
        return e;
    }

    // ---------------------------------------------------------------- tests

    private void validation() {
        out.accept("Input validation");
        check("upper case is normalised: 'Car' -> 'car'", Words.word("  Car ").equals("car"));
        check("letters of other languages allowed: 'cafe' with an accent", Words.word("café").equals("café"));
        check("apostrophe and hyphen allowed", Words.word("don't").equals("don't") && Words.word("e-mail").equals("e-mail"));
        check("empty prefix allowed (means every word)", Words.prefix("").isEmpty());
        rejects("an empty word", () -> Words.word("   "));
        rejects("two words", () -> Words.word("ice cream"));
        rejects("symbols", () -> Words.word("c@t"));
        rejects("a 65-character word", () -> Words.word("a".repeat(65)));
        rejects("a negative frequency", () -> Words.frequency("-5"));
        rejects("a frequency that is not a number", () -> Words.frequency("ten"));
        rejects("k = 0", () -> Words.count("0", "k"));
        rejects("k that is not a number", () -> Words.count("five", "k"));
    }

    private void shape() {
        out.accept("Tree shape after inserts (edge splitting)");
        RadixTree t = new RadixTree();
        t.insert("cart", 1);
        check("one word: root + 1 node holding the whole word", t.nodeCount() == 2 && t.height() == 1);
        t.insert("car", 1);
        check("'car' inside 'cart': edge split into car -> t, 3 nodes", t.nodeCount() == 3 && t.height() == 2);
        t.insert("cat", 1);
        check("'cat' splits 'car' at 'ca': glue node + 2 branches, 5 nodes", t.nodeCount() == 5);
        t.insert("dog", 1);
        check("new first letter: new child of the root, 6 nodes", t.nodeCount() == 6);
        check("compression: 4 words (13 letters) need only 8 edge characters", t.labelChars() == 8);   // ca, r, t, t, dog
        boolean added = t.insert("cat", 7);
        check("inserting an existing word updates it instead of adding", !added && t.size() == 4 && t.frequency("cat") == 7);
        check("a prefix that is not a word is not found: 'ca'", t.frequency("ca") == -1);
        check("a longer non-word is not found: 'carts'", t.frequency("carts") == -1);
        String inv = t.checkInvariants();
        check("invariants hold" + (inv == null ? "" : ": " + inv), inv == null);
    }

    private void deleting() {
        out.accept("Delete and re-compression");
        RadixTree t = new RadixTree();
        for (String w : new String[] {"car", "cart", "cat", "dog"}) t.insert(w, 1);
        check("delete a word that is not stored returns false", !t.delete("cow") && !t.delete("ca") && !t.delete("carts"));
        t.delete("car");
        check("delete 'car' (has one child 't'): merged back into one edge 'rt'", t.nodeCount() == 5 && t.labelChars() == 8
                && t.frequency("cart") == 1 && t.frequency("car") == -1);
        t.delete("cat");
        check("delete leaf 'cat': its parent 'ca' had one child left, merged into 'cart'", t.nodeCount() == 3 && t.frequency("cart") == 1);
        t.delete("cart");
        t.delete("dog");
        check("delete everything: only the root is left", t.nodeCount() == 1 && t.size() == 0);
        t.insert("car", 1);
        check("re-insert after emptying works", t.frequency("car") == 1);
        String inv = t.checkInvariants();
        check("invariants hold" + (inv == null ? "" : ": " + inv), inv == null);
    }

    private void ranking() {
        out.accept("Ranking (top-k by frequency)");
        AutocompleteEngine e = sample();
        check("complete ca 3 -> car, cat, care", words(e.complete("ca", 3)).equals("car,cat,care"));
        check("complete d 4 -> do, day, data, dog", words(e.complete("d", 4)).equals("do,day,data,dog"));
        check("equal frequencies are alphabetical: dor/dot -> dorm before dot",
                words(e.complete("do", 10)).endsWith("dorm,dot"));
        check("the prefix itself is suggested if it is a word: complete car -> car first", words(e.complete("car", 1)).equals("car"));
        check("prefix ending inside an edge: carto -> carton", words(e.complete("carto", 5)).equals("carton"));
        check("empty prefix = whole dictionary: top 2 are do, car", words(e.complete("", 2)).equals("do,car"));
        check("k larger than the matches returns all of them", e.complete("tri", 50).size() == 3);
        check("no match returns nothing", e.complete("xyz", 5).isEmpty() && e.complete("cab", 5).isEmpty());
        e.select("cattle");
        for (int i = 0; i < 70; i++) e.select("cattle");
        check("select raises a word's rank: cattle 15 -> 86 moves above cat", words(e.complete("cat", 2)).equals("cattle,cat"));
        RadixTree t = e.tree();
        t.topK("ca", 1);
        int expanded = t.lastExpanded();
        check("pruning: top-1 for 'ca' opens only " + expanded + " node(s) of the 'ca' subtree", expanded <= 2);
    }

    private void prefixQueries() {
        out.accept("Prefix listing and counting");
        AutocompleteEngine e = sample();
        RadixTree t = e.tree();
        check("count ca = 11, car = 7, x = 0, '' = 25",
                t.countPrefix("ca") == 11 && t.countPrefix("car") == 7 && t.countPrefix("x") == 0 && t.countPrefix("") == 25);
        check("prefix list is alphabetical: car, card, care, career, careful, cart, carton",
                words(t.listPrefix("car", 20)).equals("car,card,care,career,careful,cart,carton"));
        check("list limit respected", t.listPrefix("", 5).size() == 5);
        check("longest known part of 'carz' is 'car'", e.knownPart("carz").equals("car"));
        check("longest known part of 'qq' is ''", e.knownPart("qq").isEmpty());
    }

    private void improvements() {
        out.accept("Team improvements");
        check("frequency 1.5k = 1500 and 2m = 2,000,000", Words.frequency("1.5k") == 1500 && Words.frequency("2M") == 2_000_000);
        rejects("frequency 1.2345k (not whole)", () -> Words.frequency("1.2345k"));
        AutocompleteEngine e = sample();
        check("shortest unique prefix of catalog is 'cata'", e.tree().shortestUniquePrefix("catalog").equals("cata"));
        check("car has no shorter unique prefix (card, care... extend it)", e.tree().shortestUniquePrefix("car").equals("car"));
        check("unknown word has no unique prefix", e.tree().shortestUniquePrefix("zebra") == null);
        e.select("cat");
        e.select("dog");
        check("history lists selections newest first", e.history().equals(List.of("dog", "cat")));
    }

    /** Random inserts, updates and deletes on a tiny alphabet, so words share prefixes heavily. */
    private void randomised() {
        out.accept("Randomised cross-check vs HashMap, TreeMap and standard trie (" + RANDOM_OPERATIONS + " ops)");
        Random rnd = new Random(SEED);
        RadixTree radix = new RadixTree();
        Dictionary[] others = {new Baselines.HashScan(), new Baselines.SortedTree(), new Baselines.StandardTrie()};
        List<String> live = new ArrayList<>();
        String err = null;
        boolean agree = true;
        for (int i = 0; i < RANDOM_OPERATIONS; i++) {
            int op = rnd.nextInt(100);
            if (op < 25 && !live.isEmpty()) {
                String w = rnd.nextInt(5) == 0 ? randomWord(rnd) : live.get(rnd.nextInt(live.size()));
                boolean a = radix.delete(w);
                for (Dictionary d : others) agree &= d.delete(w) == a;
                if (a) live.remove(w);
            } else {
                String w = randomWord(rnd);
                long f = rnd.nextInt(50);
                boolean a = radix.insert(w, f);
                for (Dictionary d : others) agree &= d.insert(w, f) == a;
                if (a) live.add(w);
            }
            if (err == null) err = radix.checkInvariants();
            if (i % 50 == 0) {
                String r = randomWord(rnd);
                String p = r.substring(0, Math.min(r.length(), rnd.nextInt(3)));      // "", 1 or 2 letters
                int k = 1 + rnd.nextInt(8);
                List<Suggestion> expect = others[0].topK(p, k);
                agree &= radix.topK(p, k).equals(expect) && radix.topKExhaustive(p, k).equals(expect);
                for (Dictionary d : others) agree &= d.topK(p, k).equals(expect) && d.countPrefix(p) == radix.countPrefix(p);
            }
        }
        check("insert/delete results, top-k and counts agree with all 3 baselines", agree);
        check("sizes agree (" + radix.size() + " words)", radix.size() == others[0].size() && radix.size() == live.size());
        check("invariants held after every operation" + (err == null ? "" : ": " + err), err == null);
    }

    private static String randomWord(Random rnd) {
        int len = 1 + rnd.nextInt(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(3)));
        return sb.toString();
    }

    private void realWords() {
        Path p = Path.of(AutocompleteEngine.WORD_FILE);
        if (!Files.isRegularFile(p)) {
            out.accept("Real word list: skipped (" + p + " not found; run from the project folder)");
            return;
        }
        out.accept("Real 50,000-word English list");
        try {
            AutocompleteEngine e = new AutocompleteEngine();
            e.load(p);
            Baselines.SortedTree ref = new Baselines.SortedTree();
            RadixTree t = e.tree();
            t.listPrefix("", Integer.MAX_VALUE).forEach(s -> ref.insert(s.word(), s.frequency()));
            check("50,000 words loaded", t.size() == 50_000);
            check("nodes <= 2N (" + t.nodeCount() + " nodes)", t.nodeCount() <= 2 * t.size());
            Random rnd = new Random(3);
            List<Suggestion> all = t.listPrefix("", Integer.MAX_VALUE);
            boolean same = true;
            for (int i = 0; i < 2000; i++) {
                String w = all.get(rnd.nextInt(all.size())).word();
                String pre = w.substring(0, Math.min(w.length(), 1 + rnd.nextInt(4)));
                same &= t.topK(pre, 10).equals(ref.topK(pre, 10)) && t.countPrefix(pre) == ref.countPrefix(pre);
            }
            check("2,000 random prefixes: top-10 and counts identical to TreeMap", same);
            check("complete 'th' starts with 'the'", e.complete("th", 1).get(0).word().equals("the"));
            String inv = t.checkInvariants();
            check("invariants hold on the full list" + (inv == null ? "" : ": " + inv), inv == null);
        } catch (Exception ex) {
            check("real word list: " + ex, false);
        }
    }
}
