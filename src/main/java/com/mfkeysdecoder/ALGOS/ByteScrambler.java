package com.mfkeysdecoder.ALGOS;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

public class ByteScrambler {
    public static final Map<String, BinaryOperator<Integer>> candidateFunctions = new LinkedHashMap<>();
    // Cache per candidati intermedi
    private static final Map<Integer, Map<String, Object>> operationCache = new ConcurrentHashMap<>();
    // Limite massimo per la cache dei candidati temporanei (configurabile)
    private static int MAX_CACHED_CANDIDATES = 1_500_000; // Default

    // Nuovo costruttore per impostare il limite
    public ByteScrambler(int maxCachedCandidates) {
        setMaxCachedCandidates(maxCachedCandidates);
    }

    // Metodo statico per impostare il limite
    public static void setMaxCachedCandidates(int maxCachedCandidates) {
        MAX_CACHED_CANDIDATES = maxCachedCandidates;
    }

    public static int getMaxCachedCandidates() {
        return MAX_CACHED_CANDIDATES;
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

    // result: Map<result_index, List<Map<String, Object>>>
    public static Map<Integer, List<Map<String, Object>>> searchCandidates(
            List<int[]> dumps,
            int maxOperands,
            ProgressCallback progressCallback
    ) throws InterruptedException {

        int n = dumps.get(0).length;
        Map<Integer, List<Map<String, Object>>> candidateByResult = new ConcurrentHashMap<>();
        AtomicInteger progress = new AtomicInteger(0);
        long total = calculateTotalCombinations(n, maxOperands);

        if (total == Long.MAX_VALUE) {
            progressCallback.handleOverflow();
            throw new IllegalArgumentException("Too many combinations to compute without overflow.");
        }

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<?>> futures = new ArrayList<>();

        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            final int currentNumOperands = numOperands;
            List<int[]> operandCombinations = combinations(n, numOperands);

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
        operationCache.clear();

        // Deduplica i candidati per ogni indice risultato
        Map<Integer, List<Map<String, Object>>> deduped = new HashMap<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : candidateByResult.entrySet()) {
            deduped.put(entry.getKey(), deduplicate(entry.getValue()));
        }
        return deduped;
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
                && Objects.equals(a.get("reverses"), b.get("reverses"))
                && Objects.equals(a.get("result_index"), b.get("result_index"));
    }

    private static long calculateTotalCombinations(int n, int maxOperands) {
        long total = 0;
        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            long combOperands = binomial(n, numOperands);
            long negationComb = 1L << numOperands;
            long reverseComb = (numOperands == 1) ? 2 : 1L << (numOperands - 1);
            long opComb = (numOperands > 1) ? (long) Math.pow(candidateFunctions.size(), numOperands - 1) : 1;

            long product = safeMultiply(combOperands, negationComb);
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

    static class CandidateTask implements Runnable {
        private final List<int[]> dumps;
        private final int[] operandIndices;
        private final int numOperands;
        private final Map<Integer, List<Map<String, Object>>> candidateByResult;
        private final AtomicInteger progress;
        private final long total;
        private final ProgressCallback progressCallback;

        // Lista cache temporanea dei candidati validi trovati in questo task
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
            List<String> functionNames = new ArrayList<>(candidateFunctions.keySet());
            List<BinaryOperator<Integer>> functionList = new ArrayList<>(candidateFunctions.values());
            int numOps = numOperands - 1;

            List<List<Integer>> opCombos = (numOps == 0)
                    ? Collections.singletonList(Collections.emptyList())
                    : product(functionNames.size(), numOps);

            for (List<Integer> opCombo : opCombos) {
                List<List<Boolean>> negationPatterns = productBoolean(numOperands);
                for (List<Boolean> negPattern : negationPatterns) {
                    List<List<Boolean>> reversePatterns =
                            (numOperands == 1)
                                    ? productBoolean(1)
                                    : productFixedFirstTrue(numOperands);

                    for (List<Boolean> revPattern : reversePatterns) {
                        int keyHash = Objects.hash(
                                Arrays.hashCode(operandIndices),
                                opCombo.hashCode(),
                                negPattern.hashCode(),
                                revPattern.hashCode()
                        );

                        Map<String, Object> cachedCandidate = operationCache.get(keyHash);
                        if (cachedCandidate != null) {
                            addCachedCandidate(cachedCandidate);
                            progress.incrementAndGet();
                            continue;
                        }

                        List<Integer> results = new ArrayList<>();
                        boolean isValid = processCombination(opCombo, negPattern, revPattern, functionList, results);

                        if (isValid) {
                            Map<String, Object> cand = buildCandidate(
                                    opCombo, negPattern, revPattern, functionNames, results
                            );
                            addCandidateToMap(cand);
                            operationCache.put(keyHash, cand);
                            synchronized (cachedCandidates) {
                                cachedCandidates.add(cand);
                                if (cachedCandidates.size() >= MAX_CACHED_CANDIDATES) {
                                    flushCachedCandidates();

                                }
                            }
                        }

                        int current = progress.incrementAndGet();
                        if (current % 100 == 0) {
                            progressCallback.update(current, total);
                        }
                    }
                }
            }
            synchronized (cachedCandidates) {
                if (!cachedCandidates.isEmpty()) {
                    flushCachedCandidates();
                }
            }
        }

        private void flushCachedCandidates() {
            synchronized (cachedCandidates) {
                for (Map<String, Object> cand : cachedCandidates) {
                    addCandidateToMap(cand);
                }
                cachedCandidates.clear();
                operationCache.clear();
            }
        }

        private boolean processCombination(List<Integer> opCombo, List<Boolean> negPattern,
                                          List<Boolean> revPattern, List<BinaryOperator<Integer>> functionList,
                                          List<Integer> results) {
            List<BinaryOperator<Integer>> ops = new ArrayList<>();
            for (int idx : opCombo) {
                ops.add(functionList.get(idx));
            }

            Set<Integer> commonIndices = null;
            boolean valid = true;

            for (int[] dump : dumps) {
                int res = evaluateExpression(
                        dump,
                        operandIndices,
                        toPrimitive(negPattern),
                        toPrimitive(revPattern),
                        ops
                );
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
            return valid && commonIndices != null && !commonIndices.isEmpty() && uniqueResults.size() > 1;
        }

        private Map<String, Object> buildCandidate(List<Integer> opCombo, List<Boolean> negPattern,
                                                   List<Boolean> revPattern, List<String> functionNames,
                                                   List<Integer> results) {
            Map<String, Object> cand = new HashMap<>();
            cand.put("operands", operandIndices.clone());
            cand.put("negations", new ArrayList<>(negPattern));
            cand.put("reverses", new ArrayList<>(revPattern));
            cand.put("results", new ArrayList<>(results));

            List<String> opNames = new ArrayList<>();
            for (int idx : opCombo) {
                opNames.add(functionNames.get(idx));
            }
            cand.put("ops", opNames);

            Set<Integer> commonIndices = new HashSet<>();
            for (int[] dump : dumps) {
                Set<Integer> currentMatches = new HashSet<>();
                for (int k = 0; k < dump.length; k++) {
                    boolean isOperand = false;
                    for (int idx : operandIndices) {
                        if (k == idx) {
                            isOperand = true;
                            break;
                        }
                    }
                    if (!isOperand && dump[k] == results.get(dumps.indexOf(dump))) {
                        currentMatches.add(k);
                    }
                }
                if (commonIndices.isEmpty()) {
                    commonIndices.addAll(currentMatches);
                } else {
                    commonIndices.retainAll(currentMatches);
                }
            }

            if (!commonIndices.isEmpty()) {
                cand.put("result_index", Collections.min(commonIndices));
            }
            return cand;
        }

        private void addCandidateToMap(Map<String, Object> cand) {
            if (cand.containsKey("result_index")) {
                int resultIdx = (Integer) cand.get("result_index");
                candidateByResult
                        .computeIfAbsent(resultIdx, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(cand);
            }
        }

        private void addCachedCandidate(Map<String, Object> cand) {
            if (cand.containsKey("result_index")) {
                int resultIdx = (Integer) cand.get("result_index");
                candidateByResult
                        .computeIfAbsent(resultIdx, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(cand);
            }
        }
    }

    // Utility methods invariati
    private static List<int[]> combinations(int n, int r) {
        List<int[]> result = new ArrayList<>();
        combinationHelper(result, new int[r], 0, n - 1, 0);
        return result;
    }

    private static void combinationHelper(List<int[]> result, int[] data, int start, int end, int index) {
        if (index == data.length) {
            result.add(data.clone());
            return;
        }
        for (int i = start; i <= end && end - i + 1 >= data.length - index; i++) {
            data[index] = i;
            combinationHelper(result, data, i + 1, end, index + 1);
        }
    }

    private static long binomial(int n, int k) {
        long res = 1;
        for (int i = 1; i <= k; i++) {
            res = res * (n - i + 1) / i;
        }
        return res;
    }

    private static List<List<Integer>> product(int base, int length) {
        List<List<Integer>> results = new ArrayList<>();
        productHelper(results, new ArrayList<>(), base, length);
        return results;
    }

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

    private static List<List<Boolean>> productBoolean(int length) {
        List<List<Boolean>> results = new ArrayList<>();
        productBooleanHelper(results, new ArrayList<>(), length);
        return results;
    }

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

    private static List<List<Boolean>> productFixedFirstTrue(int length) {
        List<List<Boolean>> results = new ArrayList<>();
        if (length < 1) return results;
        List<Boolean> prefix = new ArrayList<>();
        prefix.add(true);
        productFixedFirstTrueHelper(results, prefix, length - 1);
        return results;
    }

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

    private static boolean[] toPrimitive(List<Boolean> list) {
        boolean[] arr = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public interface ProgressCallback {
        void update(long current, long total);
        default void handleOverflow() {}
    }
}