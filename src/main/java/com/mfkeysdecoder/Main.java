package com.mfkeysdecoder;

import com.mfkeysdecoder.ALGOS.ByteScrambler;

import java.io.*;
import java.util.*;
import javax.swing.*;
import com.google.gson.*;

public class Main {
    public static int[] parseBytes(String byteString) {
        byteString = byteString.replaceAll("\\s+", "");
        int len = byteString.length();
        int[] result = new int[len / 2];
        for (int i = 0; i < len; i += 2)
            result[i / 2] = Integer.parseInt(byteString.substring(i, i + 2), 16);
        return result;
    }

    public static List<int[]> loadDumpsFromDirectory(String directory) throws Exception {
        File dir = new File(directory);
        if (!dir.exists()) throw new FileNotFoundException("La cartella '" + directory + "' non esiste.");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length < 2) throw new Exception("Devono esserci almeno 2 file JSON nella cartella 'dumps'.");

        List<int[]> dumps = new ArrayList<>();
        Gson gson = new Gson();
        for (File f : files) {
            Reader reader = new FileReader(f);
            Map<?, ?> data = gson.fromJson(reader, Map.class);
            reader.close();
            Map<?, ?> blocks = (Map<?, ?>) data.get("blocks");
            List<Integer> keys = new ArrayList<>();
            for (Object k : blocks.keySet()) keys.add(Integer.parseInt(k.toString()));
            Collections.sort(keys);
            StringBuilder fullHex = new StringBuilder();
            for (Integer k : keys) fullHex.append(blocks.get(k.toString()));
            dumps.add(parseBytes(fullHex.toString()));
        }
        return dumps;
    }

    public static void main(String[] args) {

        long heapMaxBytes = Runtime.getRuntime().maxMemory();
        double heapMaxGB = heapMaxBytes / (1024.0 * 1024 * 1024);
        System.out.printf("Heap massimo: %.2f GB (%d bytes)%n", heapMaxGB, heapMaxBytes);

        long heapUsableBytes = (long) (heapMaxBytes * 0.7);
        double heapUsableGB = heapUsableBytes / (1024.0 * 1024 * 1024);
        System.out.printf("Heap utilizzabile per cache: %.2f GB (%d bytes)%n", heapUsableGB, heapUsableBytes);

        // Stima: 1 KB = 1000 bytes per candidato
        long maxCachedCandidates = heapUsableBytes / 1000;
        String maxCachedStr = String.format("%,d", maxCachedCandidates).replace(',', '_');
        System.out.println("MAX_CACHED_CANDIDATES ideale (heap 70%, 1KB/candidato): " + maxCachedStr);
        System.out.println();
        int safeMaxCachedCandidates = (maxCachedCandidates > Integer.MAX_VALUE)
            ? Integer.MAX_VALUE
            : (int) maxCachedCandidates;
        ByteScrambler scrambler = new ByteScrambler(safeMaxCachedCandidates);;


        SwingUtilities.invokeLater(() -> {
            try {
                List<int[]> dumps = loadDumpsFromDirectory("dumps");
                Gui app = new Gui(dumps, 16); // maxCols = 16
                app.start();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Errore: " + e.getMessage());
                System.exit(1);
            }
        });
    }
}