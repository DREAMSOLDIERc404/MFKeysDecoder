package com.mfkeysdecoder.ALGOS;

// HiddenXORFinder.java
import java.util.*;

public class HiddenXORFinder {
    private static final List<Integer> keyBytes = new ArrayList<>();
    private static final Map<Integer, Integer> nToKeyIndex = new HashMap<>();

    public static Map<Integer, List<Map<String, Object>>> searchCandidates(
        List<int[]> dumps, 
        ProgressCallback progress
    ) {
        Map<Integer, List<Map<String, Object>>> candidates = new HashMap<>();
        keyBytes.clear();
        nToKeyIndex.clear();

        int maxIndex = dumps.stream()
                           .mapToInt(dump -> dump.length)
                           .min()
                           .orElse(0) - 1;

        if (maxIndex < 1) return candidates;

        int totalOperations = maxIndex * (maxIndex + 1) / 2 * 16; // 16 combinazioni per coppia
        int currentProgress = 0;

        for (int i = 0; i <= maxIndex; i++) {
            for (int j = i + 1; j <= maxIndex; j++) {
                for (int iTrans = 0; iTrans < 4; iTrans++) {
                    boolean iNegate = (iTrans & 1) != 0;
                    boolean iReverse = (iTrans & 2) != 0;

                    for (int jTrans = 0; jTrans < 4; jTrans++) {
                        boolean jNegate = (jTrans & 1) != 0;
                        boolean jReverse = (jTrans & 2) != 0;

                        currentProgress++;
                        if (progress != null) {
                            progress.onProgress(currentProgress, totalOperations);
                        }

                        Integer commonN = null;
                        boolean valid = true;
                        List<Integer> resultsJ = new ArrayList<>();
                        List<Integer> resultsI = new ArrayList<>();

                        for (int[] dump : dumps) {
                            if (i >= dump.length || j >= dump.length) {
                                valid = false;
                                break;
                            }
                            int iByte = transformByte(dump[i], iReverse, iNegate);
                            int jByte = transformByte(dump[j], jReverse, jNegate);
                            int currentN = iByte ^ jByte;

                            if (commonN == null) {
                                commonN = currentN;
                            } else if (commonN != currentN) {
                                valid = false;
                                break;
                            }
                            resultsJ.add(jByte);
                            resultsI.add(iByte);
                        }

                        if (valid && commonN != null) {
                            int finalN = commonN;
                            int k = nToKeyIndex.computeIfAbsent(finalN, key -> {
                                keyBytes.add(finalN);
                                return keyBytes.size() - 1;
                            });

                            addCandidate(candidates, i, j, k, resultsJ, iNegate, iReverse, jNegate, jReverse);
                            addCandidate(candidates, j, i, k, resultsI, jNegate, jReverse, iNegate, iReverse);
                        }
                    }
                }
            }
        }
        return candidates;
    }

    private static int transformByte(int b, boolean reverse, boolean negate) {
        if (reverse) {
            b = ((b & 0x0F) << 4) | ((b & 0xF0) >> 4);
        }
        if (negate) {
            b ^= 0xFF;
        }
        return b;
    }

    private static void addCandidate(
        Map<Integer, List<Map<String, Object>>> candidates,
        int operand1, 
        int operand2, 
        int keyIndex,
        List<Integer> results,
        boolean negate1, boolean reverse1,
        boolean negate2, boolean reverse2
    ) {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("operands", new int[]{operand1, operand2});
        candidate.put("negations", Arrays.asList(negate1, negate2));
        candidate.put("reverses", Arrays.asList(reverse1, reverse2));
        candidate.put("ops", List.of("XOR"));
        candidate.put("result_index", keyIndex);
        candidate.put("results", results);
        
        candidates.computeIfAbsent(operand2, key -> new ArrayList<>())
                 .add(candidate);
    }

    public interface ProgressCallback {
        void onProgress(int current, int total);
    }

    public static List<Integer> getKeyBytes() {
        return new ArrayList<>(keyBytes);
    }

    public static Integer getKeyFromIndex(int keyIndex) {
        return (keyIndex >= 0 && keyIndex < keyBytes.size()) ? keyBytes.get(keyIndex) : null;
    }
}