package com.mfkeysdecoder.ALGOS;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;

public class ByteScrambler {
    public static final Map<String, BinaryOperator<Integer>> candidateFunctions = new LinkedHashMap<>();

    // Classe per chiavi di cache affidabili
    static class CacheKey {
        final int[] operandIndices;
        final List<Integer> opCombo;
        final List<Boolean> negPattern;
        final List<Boolean> revPattern;

        CacheKey(int[] operandIndices, List<Integer> opCombo, List<Boolean> negPattern, List<Boolean> revPattern) {
            this.operandIndices = operandIndices.clone();
            this.opCombo = new ArrayList<>(opCombo);
            this.negPattern = new ArrayList<>(negPattern);
            this.revPattern = new ArrayList<>(revPattern);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return Arrays.equals(operandIndices, cacheKey.operandIndices) &&
                    Objects.equals(opCombo, cacheKey.opCombo) &&
                    Objects.equals(negPattern, cacheKey.negPattern) &&
                    Objects.equals(revPattern, cacheKey.revPattern);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(opCombo, negPattern, revPattern);
            result = 31 * result + Arrays.hashCode(operandIndices);
            return result;
        }
    }

    // Limite massimo per la cache delle operazioni (configurabile)
    private static int MAX_CACHED_OPERATIONS = 1_500_000;

    // Sentinella per candidati non validi (attualmente non utilizzata direttamente)
    @SuppressWarnings("unused")
    private static final Map<String, Object> INVALID_CANDIDATE =
            Collections.singletonMap("valid", false);

    // Cache LRU per operazioni intermedie (completamente eliminabile con clear)
    private static final Map<CacheKey, Map<String, Object>> operationCache =
        Collections.synchronizedMap(new LinkedHashMap<CacheKey, Map<String, Object>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Map<String, Object>> eldest) {
                return size() > MAX_CACHED_OPERATIONS;
            }
        });

    // Costruttore per impostare il limite della cache operazioni
    public ByteScrambler(int maxCachedOperations) {
        setMaxCachedOperations(maxCachedOperations);
    }

    // Metodo statico per impostare il limite
    public static void setMaxCachedOperations(int maxCachedOperations) {
        MAX_CACHED_OPERATIONS = maxCachedOperations;
    }

    public static int getMaxCachedOperations() {
        return MAX_CACHED_OPERATIONS;
    }

    static {
        candidateFunctions.put("XOR", (a, b) -> a ^ b);
        candidateFunctions.put("ADD_MOD256", (a, b) -> (a + b) % 256);
        candidateFunctions.put("SUB_MOD256", (a, b) -> (a - b + 256) % 256);
        candidateFunctions.put("MUL_MOD256", (a, b) -> (a * b) % 256);
        candidateFunctions.put("AND", (a, b) -> a & b);
        candidateFunctions.put("OR", (a, b) -> a | b);
        candidateFunctions.put("NAND", (a, b) -> ~(a & b) & 0xFF);
        candidateFunctions.put("XNOR", (a, b) -> ~(a ^ b) & 0xFF);
        candidateFunctions.put("AVG", (a, b) -> ((a + b) / 2) % 256);
        candidateFunctions.put("DIFF", (a, b) -> Math.abs(a - b) % 256);
        candidateFunctions.put("SHIFT_XOR", (a, b) -> ((a << 2) & 0xFF) ^ b);
        candidateFunctions.put("BLEND", (a, b) -> ((a & 0xF0) | (b & 0x0F)));
    }

    public static int reverseNibble(int b) {
        return ((b & 0x0F) << 4) | (b >> 4);
    }

    public static int evaluateExpression(
            int[] data,
            int[] operandIndices,
            boolean[] negationFlags,
            boolean[] reverseFlags,
            List<BinaryOperator<Integer>> ops
    ) {
        int result = data[operandIndices[0]];
        if (negationFlags[0]) result = (~result) & 0xFF;
        if (reverseFlags[0]) result = reverseNibble(result);

        for (int i = 1; i < operandIndices.length; i++) {
            int nextVal = data[operandIndices[i]];
            if (negationFlags[i]) nextVal = (~nextVal) & 0xFF;
            if (reverseFlags[i]) nextVal = reverseNibble(nextVal);
            result = ops.get(i - 1).apply(result, nextVal);
        }
        return result;
    }

    // result: Map del risultato -> Lista di candidati (mappa contenente le info della combinazione)
    public static Map<Integer, List<Map<String, Object>>> searchCandidates(
            List<int[]> dumps,
            int maxOperands,
            ProgressCallback progressCallback
    ) throws InterruptedException {

        int n = dumps.get(0).length;
        Map<Integer, List<Map<String, Object>>> candidateByResult = new ConcurrentHashMap<>();
        AtomicInteger progress = new AtomicInteger(0);
        // Il calcolo totale tiene conto solo delle combinazioni valide
        long total = calculateTotalCombinations(n, maxOperands);

        if (total == Long.MAX_VALUE) {
            progressCallback.handleOverflow();
            throw new IllegalArgumentException("Too many combinations to compute without overflow.");
        }

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<?>> futures = new ArrayList<>();

        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            final int currentNumOperands = numOperands;
            // Genera solo le combinazioni valide (che includono almeno un indice da 0 a 15)
            List<int[]> operandCombinations = combinationsValid(n, numOperands);

            for (int[] operandIndices : operandCombinations) {
                futures.add(executor.submit(new CandidateTask(
                        dumps, operandIndices, currentNumOperands,
                        candidateByResult, progress, total, progressCallback
                )));
            }
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                throw new RuntimeException("Error during candidate search", e.getCause());
            }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        progressCallback.update(total, total);

        // Eliminazione completa e sicura della cache
        synchronized (operationCache) {
            operationCache.clear();
        }

        // Deduplica i candidati per ogni indice risultato
        Map<Integer, List<Map<String, Object>>> deduped = new HashMap<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : candidateByResult.entrySet()) {
            deduped.put(entry.getKey(), deduplicate(entry.getValue()));
        }
        return deduped;
    }

    // Calcola il totale delle operazioni basate sulle combinazioni valide: ogni combinazione
    // deve contenere almeno un indice < 16. Se n >= 16, il numero di combinazioni non valide (cioè
    // quelle prive di indici nel range [0,15]) è binomial(n - 16, r) (se possibile).
    private static long calculateTotalCombinations(int n, int maxOperands) {
        long total = 0;
        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            long allComb = binomial(n, numOperands);
            long invalidComb = 0;
            if (n >= 16 && n - 16 >= numOperands) {
                invalidComb = binomial(n - 16, numOperands);
            }
            long validComb = allComb - invalidComb; // solo le combinazioni con almeno un indice < 16

            long negationComb = 1L << numOperands;
            long reverseComb = (numOperands == 1) ? 2 : 1L << (numOperands - 1);
            long opComb = (numOperands > 1) ? (long) Math.pow(candidateFunctions.size(), numOperands - 1) : 1;

            long product = safeMultiply(validComb, negationComb);
            if (product < 0) return Long.MAX_VALUE;
            product = safeMultiply(product, reverseComb);
            if (product < 0) return Long.MAX_VALUE;
            product = safeMultiply(product, opComb);
            if (product < 0) return Long.MAX_VALUE;

            if (Long.MAX_VALUE - total < product) {
                return Long.MAX_VALUE;
            }
            total += product;
        }
        return total;
    }

    private static long safeMultiply(long a, long b) {
        if (a != 0 && b != 0 && (a * b) / a != b) {
            return -1;
        }
        return a * b;
    }

    // Genera solo combinazioni valide, ovvero quelle che contengono almeno un indice < 16
    private static List<int[]> combinationsValid(int n, int r) {
        List<int[]> result = new ArrayList<>();
        combinationHelperValid(result, new int[r], 0, n - 1, 0);
        return result;
    }

    private static void combinationHelperValid(List<int[]> result, int[] data, int start, int end, int index) {
        if (index == data.length) {
            // In questo punto, tutti gli elementi sono già validi (< 16)
            result.add(data.clone());
            return;
        }
        // Se il prossimo valore che possiamo aggiungere è >= 16,
        // allora anche tutti quelli successivi lo sono, per definizione dell'ordinamento.
        if (start >= 16) {
            return;
        }
        for (int i = start; i <= end && end - i + 1 >= data.length - index; i++) {
            // Se i è maggiore o uguale a 16, non esploriamo ulteriormente poiché
            // sappiamo che tutti i valori successivi saranno >= 16.
            if (i >= 16) break;
            data[index] = i;
            combinationHelperValid(result, data, i + 1, end, index + 1);
        }
    }

    private static List<Map<String, Object>> deduplicate(List<Map<String, Object>> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> c : list) {
            boolean found = false;
            for (Map<String, Object> d : result) {
                if (candidateEquals(c, d)) {
                    found = true;
                    break;
                }
            }
            if (!found) result.add(c);
        }
        return result;
    }

    private static boolean candidateEquals(Map<String, Object> a, Map<String, Object> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Arrays.equals((int[]) a.get("operands"), (int[]) b.get("operands"))
                && Objects.equals(a.get("ops"), b.get("ops"))
                && Objects.equals(a.get("negations"), b.get("negations"))
                && Objects.equals(a.get("reverses"), b.get("reverses"));
    }

    private static long binomial(int n, int k) {
        long res = 1;
        for (int i = 1; i <= k; i++) {
            res = res * (n - i + 1) / i;
        }
        return res;
    }

    // Metodi utilitari segnalati come "non usati" dal compilatore 
    @SuppressWarnings("unused")
    private static List<List<Integer>> product(int base, int length) {
        List<List<Integer>> results = new ArrayList<>();
        productHelper(results, new ArrayList<>(), base, length);
        return results;
    }

    @SuppressWarnings("unused")
    private static void productHelper(List<List<Integer>> results, List<Integer> current, int base, int length) {
        if (current.size() == length) {
            results.add(new ArrayList<>(current));
            return;
        }
        for (int i = 0; i < base; i++) {
            current.add(i);
            productHelper(results, current, base, length);
            current.remove(current.size() - 1);
        }
    }

    @SuppressWarnings("unused")
    private static List<List<Boolean>> productBoolean(int length) {
        List<List<Boolean>> results = new ArrayList<>();
        productBooleanHelper(results, new ArrayList<>(), length);
        return results;
    }

    @SuppressWarnings("unused")
    private static void productBooleanHelper(List<List<Boolean>> results, List<Boolean> current, int length) {
        if (current.size() == length) {
            results.add(new ArrayList<>(current));
            return;
        }
        current.add(false);
        productBooleanHelper(results, current, length);
        current.remove(current.size() - 1);
        current.add(true);
        productBooleanHelper(results, current, length);
        current.remove(current.size() - 1);
    }

    @SuppressWarnings("unused")
    private static List<List<Boolean>> productFixedFirstTrue(int length) {
        List<List<Boolean>> results = new ArrayList<>();
        if (length < 1) return results;
        List<Boolean> prefix = new ArrayList<>();
        prefix.add(true);
        productFixedFirstTrueHelper(results, prefix, length - 1);
        return results;
    }

    @SuppressWarnings("unused")
    private static void productFixedFirstTrueHelper(List<List<Boolean>> results, List<Boolean> current, int remaining) {
        if (remaining == 0) {
            results.add(new ArrayList<>(current));
            return;
        }
        current.add(false);
        productFixedFirstTrueHelper(results, current, remaining - 1);
        current.remove(current.size() - 1);
        current.add(true);
        productFixedFirstTrueHelper(results, current, remaining - 1);
        current.remove(current.size() - 1);
    }

    @SuppressWarnings("unused")
    private static boolean[] toPrimitive(List<Boolean> list) {
        boolean[] arr = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public interface ProgressCallback {
        void update(long current, long total);
        default void handleOverflow() {}
    }

    // La classe CandidateTask è ora una classe annidata statica per essere risolvibile dai metodi statici.
    private static class CandidateTask implements Runnable {
        private final List<int[]> dumps;
        private final int[] operandIndices;
        private final int numOperands;
        private final Map<Integer, List<Map<String, Object>>> candidateByResult;
        private final AtomicInteger progress;
        private final long total;
        private final ProgressCallback progressCallback;

        // Lista temporanea dei candidati validi trovati in questo task
        private final List<Map<String, Object>> cachedCandidates = Collections.synchronizedList(new ArrayList<>());

        CandidateTask(List<int[]> dumps, int[] operandIndices, int numOperands,
                      Map<Integer, List<Map<String, Object>>> candidateByResult,
                      AtomicInteger progress, long total, ProgressCallback progressCallback) {
            this.dumps = dumps;
            this.operandIndices = operandIndices;
            this.numOperands = numOperands;
            this.candidateByResult = candidateByResult;
            this.progress = progress;
            this.total = total;
            this.progressCallback = progressCallback;
        }

        @Override
        public void run() {
            try {
                List<String> functionNames = new ArrayList<>(candidateFunctions.keySet());
                List<BinaryOperator<Integer>> functionList = new ArrayList<>(candidateFunctions.values());
                int numOps = numOperands - 1;

                // Se non ci sono operazioni (quando numOperands == 1), usiamo una lista con un elemento vuoto
                List<List<Integer>> opCombos = (numOps == 0)
                        ? Collections.singletonList(Collections.emptyList())
                        : product(functionNames.size(), numOps);

                for (List<Integer> opCombo : opCombos) {
                    List<List<Boolean>> negationPatterns = productBoolean(numOperands);
                    for (List<Boolean> negPattern : negationPatterns) {
                        // Uso productBoolean per tutti gli operandi, inclusi il primo
                        List<List<Boolean>> reversePatterns = productBoolean(numOperands);
                        for (List<Boolean> revPattern : reversePatterns) {
                            CacheKey key = new CacheKey(
                                operandIndices, 
                                opCombo, 
                                negPattern, 
                                revPattern
                            );

                            Map<String, Object> cachedCandidate;
                            synchronized (operationCache) {
                                cachedCandidate = operationCache.get(key);
                            }

                            if (cachedCandidate != null) {
                                if (cachedCandidate == INVALID_CANDIDATE) {
                                    progress.incrementAndGet();
                                    continue;
                                }
                                // Aggiungo alla lista temporanea
                                synchronized (cachedCandidates) {
                                    cachedCandidates.add(cachedCandidate);
                                }
                                progress.incrementAndGet();
                                continue;
                            }

                            // Calcolo nuovo candidato
                            List<Integer> results = new ArrayList<>();
                            Set<Integer> commonIndices = processCombination(opCombo, negPattern, revPattern, functionList, results);

                            if (commonIndices != null && !commonIndices.isEmpty()) {
                                Map<String, Object> cand = buildCandidate(opCombo, negPattern, revPattern, functionNames, results, commonIndices);
                                
                                synchronized (operationCache) {
                                    operationCache.put(key, cand);
                                }
                                
                                synchronized (cachedCandidates) {
                                    cachedCandidates.add(cand);
                                }
                            } else {
                                synchronized (operationCache) {
                                    operationCache.put(key, INVALID_CANDIDATE);
                                }
                            }

                            int current = progress.incrementAndGet();
                            if (current % 100 == 0) {
                                progressCallback.update(current, total);
                            }
                        }
                    }
                }
            } finally {
                flushCachedCandidates();
            }
        }

        private void flushCachedCandidates() {
            synchronized (cachedCandidates) {
                for (Map<String, Object> cand : cachedCandidates) {
                    addCandidateToMap(cand);
                }
                cachedCandidates.clear();
            }
        }

        private Set<Integer> processCombination(List<Integer> opCombo, List<Boolean> negPattern,
                                          List<Boolean> revPattern, List<BinaryOperator<Integer>> functionList,
                                          List<Integer> results) {
            List<BinaryOperator<Integer>> ops = new ArrayList<>();
            for (int idx : opCombo) {
                ops.add(functionList.get(idx));
            }

            Set<Integer> commonIndices = null;
            boolean valid = true;

            for (int[] dump : dumps) {
                int res = evaluateExpression(dump, operandIndices, toPrimitive(negPattern), toPrimitive(revPattern), ops);
                results.add(res);

                Set<Integer> currentMatches = new HashSet<>();
                for (int k = 0; k < dump.length; k++) {
                    boolean isOperand = false;
                    for (int idx : operandIndices) {
                        if (k == idx) {
                            isOperand = true;
                            break;
                        }
                    }
                    if (!isOperand && dump[k] == res) {
                        currentMatches.add(k);
                    }
                }

                if (currentMatches.isEmpty()) {
                    valid = false;
                    break;
                }

                if (commonIndices == null) {
                    commonIndices = new HashSet<>(currentMatches);
                } else {
                    commonIndices.retainAll(currentMatches);
                }

                if (commonIndices.isEmpty()) {
                    valid = false;
                    break;
                }
            }

            Set<Integer> uniqueResults = new HashSet<>(results);
            return (valid && commonIndices != null && !commonIndices.isEmpty() && uniqueResults.size() > 1) 
                    ? commonIndices 
                    : null;
        }

        private Map<String, Object> buildCandidate(List<Integer> opCombo, List<Boolean> negPattern,
                                                   List<Boolean> revPattern, List<String> functionNames,
                                                   List<Integer> results, Set<Integer> commonIndices) {
            Map<String, Object> cand = new HashMap<>();
            cand.put("operands", operandIndices.clone());
            cand.put("negations", new ArrayList<>(negPattern));
            cand.put("reverses", new ArrayList<>(revPattern));
            cand.put("results", new ArrayList<>(results));
            cand.put("common_indices", new HashSet<>(commonIndices));

            List<String> opNames = new ArrayList<>();
            for (int idx : opCombo) {
                opNames.add(functionNames.get(idx));
            }
            cand.put("ops", opNames);
            return cand;
        }

        private void addCandidateToMap(Map<String, Object> cand) {
            Set<Integer> commonIndices = (Set<Integer>) cand.get("common_indices");
            for (int resultIdx : commonIndices) {
                candidateByResult
                        .computeIfAbsent(resultIdx, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(cand);
            }
        }
    }
}
