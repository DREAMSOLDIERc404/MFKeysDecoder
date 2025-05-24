import java.util.*;
import java.util.function.BinaryOperator;

public class ByteScrambler {
    public static final Map<String, BinaryOperator<Integer>> candidateFunctions = new LinkedHashMap<>();

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

    // Traduzione fedele della candidate_search_thread di calculation.py
         public static Map<Integer, List<Map<String, Object>>> searchCandidates(
        List<int[]> dumps,
        int maxOperands,
        ProgressCallback progressCallback
    ) {

        int n = dumps.get(0).length;
        Map<Integer, List<Map<String, Object>>> candidateByResult = new HashMap<>();

        // Calcolo totale combinazioni (per progress bar)
        long total = 0;
        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            long combOperands = binomial(n, numOperands);
            long negationComb = 1L << numOperands;
            long reverseComb = (numOperands == 1) ? 2 : 1L << (numOperands - 1);
            long opComb = (numOperands > 1) ? (long) Math.pow(candidateFunctions.size(), numOperands - 1) : 1;
            total += combOperands * negationComb * reverseComb * opComb;
        }

        long count = 0;
        List<String> functionNames = new ArrayList<>(candidateFunctions.keySet());
        List<BinaryOperator<Integer>> functionList = new ArrayList<>(candidateFunctions.values());

        for (int numOperands = 1; numOperands <= maxOperands; numOperands++) {
            List<int[]> operandCombinations = combinations(n, numOperands);
            for (int[] operandIndices : operandCombinations) {
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
                            count++;
                            if (count % 100 == 0) {
                                progressCallback.update((int) count, (int) total);
                            }
                            List<BinaryOperator<Integer>> ops = new ArrayList<>();
                            List<String> opNames = new ArrayList<>();
                            for (int idx : opCombo) {
                                ops.add(functionList.get(idx));
                                opNames.add(functionNames.get(idx));
                            }
                            List<Integer> results = new ArrayList<>();
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
                                    boolean inOperands = false;
                                    for (int val : operandIndices)
                                        if (k == val) inOperands = true;
                                    if (!inOperands && dump[k] == res) currentMatches.add(k);
                                }
                                if (currentMatches.isEmpty()) {
                                    valid = false;
                                    break;
                                }
                                if (commonIndices == null)
                                    commonIndices = currentMatches;
                                else
                                    commonIndices.retainAll(currentMatches);

                                if (commonIndices.isEmpty()) {
                                    valid = false;
                                    break;
                                }
                            }
                            if (!valid || commonIndices == null || commonIndices.isEmpty())
                                continue;
                            Set<Integer> uniqueResults = new HashSet<>(results);
                            if (uniqueResults.size() == 1) continue;
                            int commonIndex = Collections.min(commonIndices);

                            // Build candidate info
                            Map<String, Object> cand = new HashMap<>();
                            cand.put("operands", operandIndices.clone());
                            cand.put("negations", new ArrayList<>(negPattern));
                            cand.put("reverses", new ArrayList<>(revPattern));
                            cand.put("ops", new ArrayList<>(opNames));
                            cand.put("results", new ArrayList<>(results));
                            cand.put("result_index", commonIndex);

                            candidateByResult.computeIfAbsent(commonIndex, k -> new ArrayList<>()).add(cand);
                        }
                    }
                }
            }
        }
        progressCallback.update((int) total, (int) total);
        return candidateByResult;
    }

    // Utility methods: combinations, binomial, product, productBoolean, etc.
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
        void update(int current, int total);
    }
}