import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Radix tree (compressed trie) over characters, with frequency-ranked autocomplete.
 *
 * Representation
 *   - Every edge is labelled with a STRING, not a single character. A chain of
 *     nodes that has one child and ends no word is merged into one edge
 *     ("path compression"). So every node except the root either ends a word or
 *     has at least two children, which gives at most 2N nodes for N words.
 *   - A node's children are kept in an ARRAY SORTED by the first character of
 *     their labels. No two children start with the same character, so the child
 *     to follow is found by binary search, and walking children left to right
 *     visits words in alphabetical order.
 *   - Each node caches two numbers about its whole subtree:
 *       words : how many words end in it      -> countPrefix in O(p)
 *       best  : the highest frequency in it    -> top-k search can skip whole
 *                                                 subtrees that cannot win
 *
 * Complexity (L = word length, p = prefix length, s = alphabet size, N = words)
 *   insert / delete / search : O(L log s)   independent of N
 *   countPrefix              : O(p log s)
 *   topK(prefix, k)          : O(p log s + E log E), E = nodes expanded (about k x depth)
 *   space                    : O(N) nodes (at most 2N) + total label characters
 *
 * Owner: Member 2 (core data structure)
 */
public class RadixTree implements Dictionary {

    /** One node. Fields are package-private so the tests and the tree printer can inspect them. */
    static final class Node {
        String label;                       // characters on the edge from the parent ("" for root)
        Node[] kids = NO_KIDS;              // sorted by label.charAt(0)
        int nKids;
        boolean isWord;                     // a word ends exactly here
        long freq;                          // its frequency (only if isWord)
        int words;                          // words in this subtree, including this node
        long best = -1;                     // highest frequency in this subtree (-1 = none)

        Node(String label) { this.label = label; }
    }

    private static final Node[] NO_KIDS = new Node[0];

    private Node root = new Node("");
    private int nodeCount = 1;              // includes the root

    /** Filled by the last topK call: how many nodes the search expanded (for analysis). */
    private int lastExpanded;

    // =================================================================== helpers

    /** Index of the child whose label starts with c, or -(insertion point) - 1 if none. */
    private static int find(Node n, char c) {
        int lo = 0, hi = n.nKids - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            char m = n.kids[mid].label.charAt(0);
            if (m < c) lo = mid + 1;
            else if (m > c) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    private static void addChild(Node n, int at, Node child) {
        if (n.nKids == n.kids.length) n.kids = Arrays.copyOf(n.kids, Math.max(2, n.kids.length * 2));
        System.arraycopy(n.kids, at, n.kids, at + 1, n.nKids - at);
        n.kids[at] = child;
        n.nKids++;
    }

    private static void removeChild(Node n, int at) {
        System.arraycopy(n.kids, at + 1, n.kids, at, n.nKids - at - 1);
        n.kids[--n.nKids] = null;
    }

    /** How many characters word[from..] and label share at the start. */
    private static int common(String word, int from, String label) {
        int max = Math.min(word.length() - from, label.length());
        int i = 0;
        while (i < max && word.charAt(from + i) == label.charAt(i)) i++;
        return i;
    }

    /** Recomputes a node's cached words and best from itself and its children. O(children). */
    private static void refresh(Node n) {
        int w = n.isWord ? 1 : 0;
        long b = n.isWord ? n.freq : -1;
        for (int i = 0; i < n.nKids; i++) {
            w += n.kids[i].words;
            b = Math.max(b, n.kids[i].best);
        }
        n.words = w;
        n.best = b;
    }

    // =================================================================== insert

    @Override
    public boolean insert(String word, long freq) {
        if (word.isEmpty()) throw new IllegalArgumentException("word is empty");
        if (freq < 0) throw new IllegalArgumentException("frequency is negative");
        List<Node> path = new ArrayList<>();
        Node node = root;
        path.add(node);
        int i = 0;
        while (true) {
            if (i == word.length()) {
                // Case A: the word ends exactly at this node.
                boolean added = !node.isWord;
                node.isWord = true;
                node.freq = freq;
                for (int j = path.size() - 1; j >= 0; j--) refresh(path.get(j));
                return added;
            }
            int ci = find(node, word.charAt(i));
            if (ci < 0) {
                // Case B: no edge starts with the next character -> hang a new leaf here.
                Node leaf = new Node(word.substring(i));
                leaf.isWord = true;
                leaf.freq = freq;
                leaf.words = 1;
                leaf.best = freq;
                addChild(node, -(ci + 1), leaf);
                nodeCount++;
                for (int j = path.size() - 1; j >= 0; j--) {     // O(depth): counts only grow
                    Node p = path.get(j);
                    p.words++;
                    p.best = Math.max(p.best, freq);
                }
                return true;
            }
            Node child = node.kids[ci];
            int cp = common(word, i, child.label);
            if (cp == child.label.length()) {
                // Case C: the whole edge matches -> walk down it.
                node = child;
                path.add(node);
                i += cp;
                continue;
            }
            // Case D: the word leaves the edge part-way -> split the edge at cp.
            //   parent --"cart"--> X   becomes   parent --"car"--> mid --"t"--> X
            Node mid = new Node(child.label.substring(0, cp));
            child.label = child.label.substring(cp);
            mid.kids = new Node[] {child, null};
            mid.nKids = 1;
            mid.words = child.words;
            mid.best = child.best;
            node.kids[ci] = mid;                    // same first character, so the order is kept
            nodeCount++;
            node = mid;
            path.add(node);
            i += cp;
            // loop again: the word either ends at mid (case A) or gets a new leaf under it (case B)
        }
    }

    // =================================================================== delete

    @Override
    public boolean delete(String word) {
        if (word.isEmpty()) return false;
        List<Node> path = new ArrayList<>();
        List<Integer> slot = new ArrayList<>();          // index of path[j] inside path[j-1]
        Node node = root;
        path.add(node);
        slot.add(-1);
        int i = 0;
        while (i < word.length()) {
            int ci = find(node, word.charAt(i));
            if (ci < 0) return false;
            Node child = node.kids[ci];
            if (common(word, i, child.label) != child.label.length()) return false;
            node = child;
            path.add(node);
            slot.add(ci);
            i += child.label.length();
        }
        if (!node.isWord) return false;

        node.isWord = false;
        node.freq = 0;
        int last = path.size() - 1;
        Node parent = path.get(last - 1);

        if (node.nKids == 0) {
            // Leaf: unlink it. The parent may be left as a non-word with one child -> merge it.
            removeChild(parent, slot.get(last));
            nodeCount--;
            path.remove(last);
            if (parent != root && !parent.isWord && parent.nKids == 1) mergeWithOnlyChild(parent);
        } else if (node.nKids == 1) {
            // Non-word node with one child is useless: absorb the child.
            mergeWithOnlyChild(node);
        }
        // With two or more children the node stays: it is still a branching point.

        for (int j = path.size() - 1; j >= 0; j--) refresh(path.get(j));
        return true;
    }

    /** n --"ca"--> c --"rt"--> ...   becomes   n --"cart"--> ...   (n keeps its place in its parent). */
    private void mergeWithOnlyChild(Node n) {
        Node c = n.kids[0];
        n.label = n.label + c.label;
        n.kids = c.kids;
        n.nKids = c.nKids;
        n.isWord = c.isWord;
        n.freq = c.freq;
        n.words = c.words;
        n.best = c.best;
        nodeCount--;
    }

    // =================================================================== search

    /** Node where `word` ends exactly, or null. */
    private Node exact(String word) {
        Node node = root;
        int i = 0;
        while (i < word.length()) {
            int ci = find(node, word.charAt(i));
            if (ci < 0) return null;
            Node child = node.kids[ci];
            if (common(word, i, child.label) != child.label.length()) return null;
            node = child;
            i += child.label.length();
        }
        return node;
    }

    @Override
    public long frequency(String word) {
        Node n = exact(word);
        return n != null && n.isWord ? n.freq : -1;
    }

    public boolean contains(String word) { return frequency(word) >= 0; }

    /** Adds delta to a stored word's frequency (a user picked this suggestion). */
    public long boost(String word, long delta) {
        long f = frequency(word);
        if (f < 0) return -1;
        insert(word, f + delta);
        return f + delta;
    }

    // =================================================================== prefix search

    /** Where a prefix ends in the tree: the node below it, and that node's full text. */
    static final class Locus {
        final Node node;
        final String text;          // the string spelled from the root to node (starts with the prefix)
        Locus(Node node, String text) { this.node = node; this.text = text; }
    }

    /**
     * Finds the node whose subtree holds exactly the words starting with prefix.
     * The prefix may end in the middle of an edge ("ca" ends inside the edge "cart").
     * Returns null if no word starts with prefix.
     */
    Locus locate(String prefix) {
        Node node = root;
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < prefix.length()) {
            int ci = find(node, prefix.charAt(i));
            if (ci < 0) return null;
            Node child = node.kids[ci];
            int cp = common(prefix, i, child.label);
            if (i + cp == prefix.length()) return new Locus(child, text.append(child.label).toString());
            if (cp < child.label.length()) return null;                // mismatch inside the edge
            text.append(child.label);
            node = child;
            i += cp;
        }
        return new Locus(node, text.toString());
    }

    @Override
    public int countPrefix(String prefix) {
        Locus l = locate(prefix);
        return l == null ? 0 : l.node.words;
    }

    /**
     * The k most frequent words starting with prefix, best first (ties alphabetical).
     *
     * Best-first search: a priority queue holds subtrees keyed by their cached `best`
     * and finished words keyed by their frequency. A subtree's key is an upper bound
     * on every word inside it, so when a word reaches the front of the queue no
     * waiting subtree can contain a better one. Subtrees whose best is too low are
     * never opened.
     */
    @Override
    public List<Suggestion> topK(String prefix, int k) {
        List<Suggestion> out = new ArrayList<>(k);
        lastExpanded = 0;
        Locus l = locate(prefix);
        if (l == null || k <= 0) return out;
        PriorityQueue<Entry> pq = new PriorityQueue<>();
        pq.add(new Entry(l.node.best, l.node, null, l.text, false));
        while (!pq.isEmpty() && out.size() < k) {
            Entry e = pq.poll();
            if (e.isWord) {                                 // a finished word: nothing left can beat it
                out.add(new Suggestion(e.text(), e.key));
                continue;
            }
            lastExpanded++;
            Node n = e.node;
            if (n.isWord) pq.add(new Entry(n.freq, n, e.parent, e.text, true));
            for (int i = 0; i < n.nKids; i++) {
                Node c = n.kids[i];
                pq.add(new Entry(c.best, c, e, null, false));
            }
        }
        return out;
    }

    /**
     * Queue entry: a subtree (key = its best) or a finished word (key = its frequency).
     * The entry's text (the string from the root) is built only when it is needed -
     * to return a word, or to break a tie between equal keys - by asking the parent
     * entry for its text and appending this node's label.
     */
    private static final class Entry implements Comparable<Entry> {
        final long key; final Node node; final Entry parent; final boolean isWord;
        String text;
        Entry(long key, Node node, Entry parent, String text, boolean isWord) {
            this.key = key; this.node = node; this.parent = parent; this.text = text; this.isWord = isWord;
        }
        String text() {
            if (text == null) text = parent.text() + node.label;
            return text;
        }
        @Override public int compareTo(Entry o) {
            if (key != o.key) return Long.compare(o.key, key);          // higher key first
            return text().compareTo(o.text());                          // then alphabetical
        }
    }

    /** Nodes expanded by the last topK call. */
    public int lastExpanded() { return lastExpanded; }

    /**
     * Same answer as topK, but by visiting EVERY word under the prefix and keeping
     * the best k. Used by the benchmark to show what the `best` pruning saves.
     */
    public List<Suggestion> topKExhaustive(String prefix, int k) {
        Locus l = locate(prefix);
        List<Suggestion> out = new ArrayList<>();
        if (l == null) return out;
        PriorityQueue<Suggestion> keep = new PriorityQueue<>(Suggestion.RANK.reversed());
        collect(l.node, new StringBuilder(l.text), keep, k);
        out.addAll(keep);
        out.sort(Suggestion.RANK);
        return out;
    }

    private void collect(Node n, StringBuilder text, PriorityQueue<Suggestion> keep, int k) {
        if (n.isWord) {
            keep.add(new Suggestion(text.toString(), n.freq));
            if (keep.size() > k) keep.poll();
        }
        for (int i = 0; i < n.nKids; i++) {
            int len = text.length();
            text.append(n.kids[i].label);
            collect(n.kids[i], text, keep, k);
            text.setLength(len);
        }
    }

    /** All words starting with prefix in alphabetical order, at most `limit` of them. */
    public List<Suggestion> listPrefix(String prefix, int limit) {
        List<Suggestion> out = new ArrayList<>();
        Locus l = locate(prefix);
        if (l != null) alphabetical(l.node, new StringBuilder(l.text), out, limit);
        return out;
    }

    private void alphabetical(Node n, StringBuilder text, List<Suggestion> out, int limit) {
        if (out.size() >= limit) return;
        if (n.isWord) out.add(new Suggestion(text.toString(), n.freq));   // a word comes before its extensions
        for (int i = 0; i < n.nKids && out.size() < limit; i++) {
            int len = text.length();
            text.append(n.kids[i].label);
            alphabetical(n.kids[i], text, out, limit);
            text.setLength(len);
        }
    }

    /** Length of the longest start of `query` that some stored word also starts with. */
    public int longestKnownPrefix(String query) {
        Node node = root;
        int i = 0;
        while (i < query.length()) {
            int ci = find(node, query.charAt(i));
            if (ci < 0) return i;
            Node child = node.kids[ci];
            int cp = common(query, i, child.label);
            if (cp < child.label.length()) return i + cp;
            node = child;
            i += cp;
        }
        return i;
    }

    /** Lines describing how `prefix` is matched edge by edge (for the trace command). */
    public List<String> trace(String prefix) {
        List<String> lines = new ArrayList<>();
        Node node = root;
        int i = 0, step = 1;
        while (i < prefix.length()) {
            int ci = find(node, prefix.charAt(i));
            if (ci < 0) {
                lines.add(String.format("  step %d: no edge starting with '%c' among %d children -> no words",
                        step, prefix.charAt(i), node.nKids));
                return lines;
            }
            Node child = node.kids[ci];
            int cp = common(prefix, i, child.label);
            String verdict;
            if (i + cp == prefix.length()) {
                verdict = cp == child.label.length() ? "prefix ends at the end of this edge"
                                                     : "prefix ends inside this edge (" + cp + " of " + child.label.length() + " chars)";
                lines.add(String.format("  step %d: binary search %d children for '%c' -> edge \"%s\": %s",
                        step, node.nKids, prefix.charAt(i), child.label, verdict));
                lines.add(String.format("  subtree holds %d words, best frequency %d", child.words, child.best));
                return lines;
            }
            if (cp < child.label.length()) {
                lines.add(String.format("  step %d: edge \"%s\" differs from the prefix after %d chars -> no words",
                        step, child.label, cp));
                return lines;
            }
            lines.add(String.format("  step %d: binary search %d children for '%c' -> edge \"%s\" matches, go down",
                    step++, node.nKids, prefix.charAt(i), child.label));
            node = child;
            i += cp;
        }
        lines.add(String.format("  prefix is empty -> whole tree: %d words, best frequency %d", root.words, root.best));
        return lines;
    }

    // =================================================================== stats and printing

    @Override public int size()     { return root.words; }
    public int nodeCount()          { return nodeCount; }
    @Override public void clear()   { root = new Node(""); nodeCount = 1; }
    @Override public String name()  { return "Radix tree"; }

    /** Longest root-to-leaf path in nodes (root not counted). */
    public int height() { return height(root) - 1; }
    private int height(Node n) {
        int h = 0;
        for (int i = 0; i < n.nKids; i++) h = Math.max(h, height(n.kids[i]));
        return h + 1;
    }

    /** Total characters stored on edges. */
    public long labelChars() { return labelChars(root); }
    private long labelChars(Node n) {
        long s = n.label.length();
        for (int i = 0; i < n.nKids; i++) s += labelChars(n.kids[i]);
        return s;
    }

    /** Draws the subtree under prefix, at most maxLines lines. */
    public String render(String prefix, int maxLines) {
        Locus l = locate(prefix);
        if (l == null) return "  (no words start with '" + prefix + "')\n";
        StringBuilder sb = new StringBuilder();
        int[] lines = {0};
        String head = l.node == root ? "(root)" : "\"" + l.text + "\"";
        sb.append("  ").append(head).append(describe(l.node)).append('\n');
        lines[0]++;
        draw(l.node, "  ", sb, lines, maxLines);
        if (lines[0] >= maxLines) sb.append("  ... (more not shown; use a longer prefix)\n");
        return sb.toString();
    }

    private void draw(Node n, String indent, StringBuilder sb, int[] lines, int max) {
        for (int i = 0; i < n.nKids && lines[0] < max; i++) {
            Node c = n.kids[i];
            boolean lastKid = i == n.nKids - 1;
            sb.append(indent).append(lastKid ? "`-- " : "|-- ").append(c.label).append(describe(c)).append('\n');
            lines[0]++;
            draw(c, indent + (lastKid ? "    " : "|   "), sb, lines, max);
        }
    }

    private static String describe(Node n) {
        return (n.isWord ? "  * word, freq " + n.freq : "") + "   [" + n.words + " words, best " + n.best + "]";
    }

    /**
     * Checks every structural rule. Returns null if the tree is valid, otherwise what is wrong.
     * Used by the tests after every insert and delete.
     */
    String checkInvariants() {
        int[] nodes = {0};
        String e = check(root, true, nodes);
        if (e != null) return e;
        if (nodes[0] != nodeCount) return "nodeCount is " + nodeCount + " but the tree has " + nodes[0] + " nodes";
        if (nodeCount > 2 * Math.max(1, root.words)) return "more than 2N nodes";
        return null;
    }

    private String check(Node n, boolean isRoot, int[] nodes) {
        nodes[0]++;
        if (!isRoot && n.label.isEmpty()) return "empty edge label";
        if (!isRoot && !n.isWord && n.nKids < 2) return "node \"" + n.label + "\" is not a word and has " + n.nKids + " child(ren)";
        int w = n.isWord ? 1 : 0;
        long b = n.isWord ? n.freq : -1;
        for (int i = 0; i < n.nKids; i++) {
            Node c = n.kids[i];
            if (i > 0 && n.kids[i - 1].label.charAt(0) >= c.label.charAt(0)) return "children of \"" + n.label + "\" not sorted or share a first character";
            String e = check(c, false, nodes);
            if (e != null) return e;
            w += c.words;
            b = Math.max(b, c.best);
        }
        if (w != n.words) return "cached word count wrong at \"" + n.label + "\"";
        if (b != n.best) return "cached best frequency wrong at \"" + n.label + "\"";
        return null;
    }
}
