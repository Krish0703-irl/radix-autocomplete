# Radix Tree Autocomplete

A Java autocomplete demo powered by a **compressed radix tree**. It returns
frequency-ranked suggestions as you type, counts and lists prefix matches, and
learns from selections by increasing the chosen word's frequency. The project
also compares the radix tree with `TreeMap`, `HashMap`, and a standard trie.

## Live demo

The screenshots below are from the running Swing demo, using the included
50,000-word dictionary.

| Prefix search | Selection learning |
|---|---|
| ![Suggestions ranked for the prefix “ca”](docs/screenshots/01-prefix-ranking.png) | ![Selecting “can” updates its frequency and history](docs/screenshots/02-selection-history.png) |

| No matching words | A new word learned from the demo |
|---|---|
| ![No matches for an unknown prefix](docs/screenshots/03-no-match.png) | ![The new word is stored and appears in selection history](docs/screenshots/04-new-word-learned.png) |

## Automated tests

The current test run reports **56 passed, 0 failed**. Coverage includes input
validation, radix-tree edge splitting and merging, prefix queries, top-k ranking,
20,000 randomized operations checked against three baseline implementations,
and prefix checks over a 50,000-word dictionary.

A search box suggests words as you type. This project stores a dictionary in a
**radix tree** (compressed trie) and answers, for any typed prefix:

- the **top-k most frequent** words starting with it (autocomplete),
- **all** words starting with it, in alphabetical order,
- **how many** words start with it,

and supports adding, deleting and searching words, plus learning from what users pick.

Two ideas make it fast:

1. **Path compression.** Chains of single-child nodes are merged into one edge
   labelled with a string, so the tree has at most 2N nodes (59,330 nodes for
   50,000 English words, against 116,080 in a standard trie).
2. **Subtree summaries.** Every node caches how many words are below it and the
   highest frequency below it. Counting is O(prefix length), and the top-k search
   opens only the subtrees that can still win: about 24 nodes for a 1-letter prefix
   matching 2,848 words.

## Run it

Requirements: JDK 17 or newer.

**VS Code** (Extension Pack for Java): open this folder, open `src/Main.java`, press **Ctrl+F5**.

**Terminal**
```bash
./run.sh            # interactive demo
./run.sh test       # automated checks
./run.sh bench      # benchmark (about 2 minutes)
```
## Commands

| Command | Example | What it does |
|---|---|---|
| `add <word> [freq]` | `add carton 10` | add a word or set its frequency |
| `del <word>` | `del car` | delete a word (the tree re-compresses) |
| `search <word>` | `search careful` | is it stored, with what frequency |
| `complete <prefix> [k]` (or `c`) | `complete ca 5` | top-k suggestions by frequency |
| `prefix <prefix> [n]` | `prefix car` | all words with the prefix, alphabetical |
| `count <prefix>` | `count ca` | number of words with the prefix |
| `select <word>` | `select cattle` | the user picked it: frequency + 1 |
| `trace <prefix>` | `trace carto` | how the prefix is found, edge by edge |
| `tree [prefix]` | `tree ca` | draw the tree with subtree summaries |
| `sample`, `words`, `load <file>` | `words` | 25 demo words / 50,000 English words / a file |
| `stats`, `test`, `bench [quick]`, `clear`, `help`, `exit` | | |

Bad input never crashes the program; it prints the reason and waits for the next command.

## Files and owners

| File | Role | Owner |
|---|---|---|
| `Words.java`, `Suggestion.java` | input validation and normalisation; result type and ranking order | Member 1 |
| `Baselines.java` | HashMap + scan, TreeMap (red-black BST), standard trie | Member 1 |
| **`RadixTree.java`** | **the data structure**: insert with edge splitting, delete with merging, prefix locate, best-first top-k, count, list, invariant checker | Member 2 |
| `Dictionary.java`, `AutocompleteEngine.java`, `Main.java` | common interface, autocomplete service, interactive CLI | Member 3 |
| `SelfTest.java`, `Benchmark.java` | tests and performance study | Member 4 |

## Complexity (L = word length, p = prefix length, s = alphabet size, N = words, k = suggestions)

| Operation | Radix tree | TreeMap | Standard trie | HashMap |
|---|---|---|---|---|
| insert / delete / search | O(L log s) | O(L log N) | O(L) | O(L) average |
| count words with prefix | **O(p log s)** | O(m), m = matches | O(subtree) | O(N·p) |
| top-k with prefix | **O(p log s + E log E)**, E ≈ k × depth nodes expanded | O(m log k) | O(subtree) | O(N·p) |
| space | ≤ 2N nodes | N entries | ≤ total characters | N entries |

## Benchmark highlights (sandbox run, top-10; your numbers will differ)

| | 50,000 English words | 500,000 generated words |
|---|---|---|
| Radix tree, 1-letter prefix | 18 µs | 19 µs |
| TreeMap, 1-letter prefix | 79 µs | 818 µs |
| Standard trie, 1-letter prefix | 488 µs | 17,969 µs |
| Radix tree count (2 letters) | 0.11 µs | 0.15 µs |
| TreeMap count (2 letters) | 8.9 µs | 132 µs |
| Radix tree nodes / standard trie nodes | 59,330 / 116,080 | 559,397 / 1,351,652 |

The radix tree's top-k time stays almost flat as the dictionary grows; the others grow
with the number of matching words. Honest limits: for 3-letter prefixes with few
matches TreeMap is about as fast, and the radix tree uses more memory than TreeMap
(6.1 MB vs 2.5 MB at 50k words; TreeMap's figure excludes the word strings, which the
input list already holds). Full output: `results/benchmark_output.txt`.

## Data

`data/words_50k.txt`: the 50,000 most frequent English words with frequencies,
derived from the [wordfreq](https://github.com/rspeer/wordfreq) project (data licensed CC BY-SA 4.0).
