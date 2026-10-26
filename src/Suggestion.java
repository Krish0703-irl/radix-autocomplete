import java.util.Comparator;

/**
 * One autocomplete result: a word and how often it is used.
 *
 * RANK is the single ordering every dictionary uses: higher frequency first, and
 * for equal frequency, alphabetical order. Because all structures break ties the
 * same way, their answers can be compared exactly in the tests and benchmark.
 *
 * Owner: Member 1 (input layer + baselines)
 */
public record Suggestion(String word, long frequency) {

    public static final Comparator<Suggestion> RANK =
            Comparator.comparingLong(Suggestion::frequency).reversed().thenComparing(Suggestion::word);

    @Override
    public String toString() {
        return word + " (" + frequency + ")";
    }
}
