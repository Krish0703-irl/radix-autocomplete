import java.util.List;

/**
 * Common interface for every word store in the project, so the radix tree and
 * the baseline structures are tested and benchmarked with identical code.
 * Words and prefixes passed in are already normalised (see Words).
 *
 * Owner: Member 3 (engine layer)
 */
public interface Dictionary {

    /** Adds the word, or sets its frequency if it is already stored. Returns true if it was new. */
    boolean insert(String word, long frequency);

    /** Removes the word. Returns false if it was not stored. */
    boolean delete(String word);

    /** The word's frequency, or -1 if the word is not stored. */
    long frequency(String word);

    /** The k most frequent words that start with prefix, ranked by Suggestion.RANK. */
    List<Suggestion> topK(String prefix, int k);

    /** How many stored words start with prefix. */
    int countPrefix(String prefix);

    int size();

    void clear();

    String name();
}
