import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The three structures the radix tree is compared against. Each one gives exactly
 * the same answers (same ranking, same tie-breaking); only the speed and memory differ.
 *
 * Owner: Member 1 (input layer + baselines)
 */
public final class Baselines {

    private Baselines() { }

    /** Keeps the best k of a stream of suggestions (a min-heap of size k). */
    static final class TopK {
        private final int k;
        private final PriorityQueue<Suggestion> heap = new PriorityQueue<>(Suggestion.RANK.reversed());
        TopK(int k) { this.k = k; }
        void offer(String word, long freq) {
            if (heap.size() < k) { heap.add(new Suggestion(word, freq)); return; }
            Suggestion worst = heap.peek();
            if (freq > worst.frequency() || (freq == worst.frequency() && word.compareTo(worst.word()) < 0)) {
                heap.poll();
                heap.add(new Suggestion(word, freq));
            }
        }
        List<Suggestion> result() {
            List<Suggestion> out = new ArrayList<>(heap);
            out.sort(Suggestion.RANK);
            return out;
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Baseline 1: HashMap. Exact search is O(L) on average, but a hash table has
     * no idea which keys share a prefix, so every prefix query scans all N words.
     */
    public static final class HashScan implements Dictionary {
        private final Map<String, Long> map = new HashMap<>();
        @Override public boolean insert(String w, long f) { return map.put(w, f) == null; }
        @Override public boolean delete(String w)          { return map.remove(w) != null; }
        @Override public long frequency(String w)          { Long f = map.get(w); return f == null ? -1 : f; }
        @Override public List<Suggestion> topK(String prefix, int k) {
            TopK t = new TopK(k);
            for (Map.Entry<String, Long> e : map.entrySet()) {
                if (e.getKey().startsWith(prefix)) t.offer(e.getKey(), e.getValue());
            }
            return t.result();
        }
        @Override public int countPrefix(String prefix) {
            int c = 0;
            for (String w : map.keySet()) if (w.startsWith(prefix)) c++;
            return c;
        }
        @Override public int size()    { return map.size(); }
        @Override public void clear()  { map.clear(); }
        @Override public String name() { return "HashMap + full scan"; }
    }

    // ------------------------------------------------------------------------
    /**
     * Baseline 2: balanced BST (Java's TreeMap, a red-black tree). Words with a
     * common prefix are next to each other in sorted order, so the prefix range is
     * found in O(L log N). But the top-k still has to look at every word in that
     * range (m words), and counting the range is also O(m).
     */
    public static final class SortedTree implements Dictionary {
        private final TreeMap<String, Long> map = new TreeMap<>();
        @Override public boolean insert(String w, long f) { return map.put(w, f) == null; }
        @Override public boolean delete(String w)          { return map.remove(w) != null; }
        @Override public long frequency(String w)          { Long f = map.get(w); return f == null ? -1 : f; }
        private SortedMap<String, Long> range(String prefix) {
            if (prefix.isEmpty()) return map;
            return map.subMap(prefix, prefix + Character.MAX_VALUE);
        }
        @Override public List<Suggestion> topK(String prefix, int k) {
            TopK t = new TopK(k);
            for (Map.Entry<String, Long> e : range(prefix).entrySet()) t.offer(e.getKey(), e.getValue());
            return t.result();
        }
        @Override public int countPrefix(String prefix) { return range(prefix).size(); }
        @Override public int size()    { return map.size(); }
        @Override public void clear()  { map.clear(); }
        @Override public String name() { return "TreeMap (red-black BST)"; }
    }

    // ------------------------------------------------------------------------
    /**
     * Baseline 3: standard (uncompressed) trie, one node per character, children in
     * a HashMap. Prefix search is O(p) like the radix tree, but it has many more
     * nodes, and without cached subtree data a top-k query must visit every word
     * under the prefix.
     */
    public static final class StandardTrie implements Dictionary {
        private static final class Node {
            final Map<Character, Node> kids = new HashMap<>(4);
            boolean isWord;
            long freq;
        }
        private Node root = new Node();
        private int size;
        private long nodes = 1;

        @Override public boolean insert(String w, long f) {
            Node n = root;
            for (int i = 0; i < w.length(); i++) {
                Node c = n.kids.get(w.charAt(i));
                if (c == null) { c = new Node(); n.kids.put(w.charAt(i), c); nodes++; }
                n = c;
            }
            boolean added = !n.isWord;
            n.isWord = true;
            n.freq = f;
            if (added) size++;
            return added;
        }

        @Override public boolean delete(String w) {
            Node[] path = new Node[w.length() + 1];
            Node n = root;
            path[0] = n;
            for (int i = 0; i < w.length(); i++) {
                n = n.kids.get(w.charAt(i));
                if (n == null) return false;
                path[i + 1] = n;
            }
            if (!n.isWord) return false;
            n.isWord = false;
            size--;
            for (int i = w.length(); i > 0; i--) {                  // prune nodes left empty
                if (path[i].isWord || !path[i].kids.isEmpty()) break;
                path[i - 1].kids.remove(w.charAt(i - 1));
                nodes--;
            }
            return true;
        }

        private Node walk(String s) {
            Node n = root;
            for (int i = 0; i < s.length() && n != null; i++) n = n.kids.get(s.charAt(i));
            return n;
        }

        @Override public long frequency(String w) {
            Node n = walk(w);
            return n != null && n.isWord ? n.freq : -1;
        }

        @Override public List<Suggestion> topK(String prefix, int k) {
            TopK t = new TopK(k);
            Node n = walk(prefix);
            if (n != null) collect(n, new StringBuilder(prefix), t);
            return t.result();
        }

        private void collect(Node n, StringBuilder sb, TopK t) {
            if (n.isWord) t.offer(sb.toString(), n.freq);
            for (Map.Entry<Character, Node> e : n.kids.entrySet()) {
                sb.append(e.getKey());
                collect(e.getValue(), sb, t);
                sb.setLength(sb.length() - 1);
            }
        }

        @Override public int countPrefix(String prefix) {
            Node n = walk(prefix);
            return n == null ? 0 : count(n);
        }

        private int count(Node n) {
            int c = n.isWord ? 1 : 0;
            for (Node k : n.kids.values()) c += count(k);
            return c;
        }

        public long nodeCount()        { return nodes; }
        @Override public int size()    { return size; }
        @Override public void clear()  { root = new Node(); size = 0; nodes = 1; }
        @Override public String name() { return "Standard trie (1 char/node)"; }
    }
}
