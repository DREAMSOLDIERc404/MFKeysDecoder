package com.mfkeysdecoder.ALGOS;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;

public class ByteScrambler {
    public static final Map<String, BinaryOperator<Integer>> candidateFunctions = new LinkedHashMap<>();
    private static final Map<Integer, Boolean> operationCache = new ConcurrentHashMap<>();

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
        return new HashMap<>(candidateByResult);
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

                        Boolean cachedValidity = operationCache.get(keyHash);
                        if (cachedValidity != null) {
                            if (cachedValidity) {
                                addCachedCandidate(keyHash);
                            }
                            progress.incrementAndGet();
                            continue;
                        }

                        boolean isValid = processCombination(opCombo, negPattern, revPattern, functionList);
                        operationCache.put(keyHash, isValid);

                        if (isValid) {
                            addCandidateToMap(opCombo, negPattern, revPattern, functionNames);
                        }

                        int current = progress.incrementAndGet();
                        if (current % 100 == 0) {
                            progressCallback.update(current, total);
                        }
                    }
                }
            }
        }

        private boolean processCombination(List<Integer> opCombo, List<Boolean> negPattern,
                                          List<Boolean> revPattern, List<BinaryOperator<Integer>> functionList) {
            List<BinaryOperator<Integer>> ops = new ArrayList<>();
            for (int idx : opCombo) {
                ops.add(functionList.get(idx));
            }

            Set<Integer> commonIndices = null;
            boolean valid = true;
            List<Integer> results = new ArrayList<>();

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
            return valid && !commonIndices.isEmpty() && uniqueResults.size() > 1;
        }

        private void addCandidateToMap(List<Integer> opCombo, List<Boolean> negPattern,
                                      List<Boolean> revPattern, List<String> functionNames) {
            Map<String, Object> cand = new HashMap<>();
            cand.put("operands", operandIndices.clone());
            cand.put("negations", new ArrayList<>(negPattern));
            cand.put("reverses", new ArrayList<>(revPattern));

            List<String> opNames = new ArrayList<>();
            for (int idx : opCombo) {
                opNames.add(functionNames.get(idx));
            }
            cand.put("ops", opNames);

            int commonIndex = findCommonIndex();
            cand.put("result_index", commonIndex);

            candidateByResult.computeIfAbsent(commonIndex, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(cand);
        }

        private void addCachedCandidate(int keyHash) {
            // Implement logic to retrieve cached candidate if needed
            // (This would require additional caching structures)
        }

        private int findCommonIndex() {
            // Simplified for brevity. Actual logic should compute the common index.
            return operandIndices[0];
        }
    }

    // Utility methods
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