import java.util.*;
import java.util.stream.Collectors;

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

        int totalOperations = maxIndex * (maxIndex + 1) / 2;
        int currentProgress = 0;

        for (int i = 0; i <= maxIndex; i++) {
            for (int j = i + 1; j <= maxIndex; j++) {
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
                    int currentN = dump[i] ^ dump[j];
                    if (commonN == null) {
                        commonN = currentN;
                    } else if (commonN != currentN) {
                        valid = false;
                        break;
                    }
                    resultsJ.add(dump[j]);
                    resultsI.add(dump[i]);
                }

                if (valid && commonN != null) {
                    int finalN = commonN; // Variabile effectively final
                    int k = nToKeyIndex.computeIfAbsent(finalN, key -> {
                        keyBytes.add(finalN);
                        return keyBytes.size() - 1;
                    });

                    addCandidate(candidates, i, k, j, resultsJ);
                    addCandidate(candidates, j, k, i, resultsI);
                }
            }
        }

        return candidates;
    }

    private static void addCandidate(
        Map<Integer, List<Map<String, Object>>> candidates,
        int operand1, 
        int operand2, 
        int resultIndex,
        List<Integer> results
    ) {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("operands", new int[]{operand1, operand2});
        candidate.put("negations", Arrays.asList(false, false));
        candidate.put("reverses", Arrays.asList(false, false));
        candidate.put("ops", List.of("XOR"));
        candidate.put("result_index", resultIndex);
        candidate.put("results", results);
        
        candidates.computeIfAbsent(resultIndex, key -> new ArrayList<>())
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