package com.mfkeysdecoder;

import com.mfkeysdecoder.ALGOS.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

public class Gui extends JFrame {
    private final List<int[]> dumps;
    private final int maxCols;
    private Map<Integer, List<Map<String, Object>>> candidateByResult;

    private static class GlobalSelection {
        Integer selectedDumpIndex = null;
        Integer selectedByteIndex = null;
        Map<String, Object> selectedCandidate = null;
    }
    private final GlobalSelection globalSelection = new GlobalSelection();

    private String selectedAlgorithm;
    private JPanel inputPanel;
    private JSpinner operandsSpinner;
    private JPanel progressPanel;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    private JTabbedPane tabbedPane;
    private JTable candidateTable;
    private DefaultTableModel tableModel;
    private Map<Integer, Map<Integer, JLabel>> dumpLabelMap = new HashMap<>();

    private int maxOperands;

    public Gui(List<int[]> dumps, int maxCols) {
        this.dumps = dumps;
        this.maxCols = maxCols;

        setTitle("Visualizzazione Dump");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(420, 250);
        setResizable(false);
        setLocationRelativeTo(null);

        buildInputPanel();
        add(inputPanel, BorderLayout.CENTER);
    }

    private List<Map<String, Object>> getCurrentCandidates() {
        if (globalSelection.selectedByteIndex == null || candidateByResult == null)
            return null;
        List<Map<String, Object>> all = candidateByResult.get(globalSelection.selectedByteIndex);
        if (all == null) return null;
        return all;
    }

    private void buildProgressPanel() {
        progressPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(18, 0, 6, 0);
        progressLabel = new JLabel("Calcolo in corso...");
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 13f));
        progressPanel.add(progressLabel, gbc);

        gbc.gridy = 1; gbc.insets = new Insets(0, 0, 10, 0);
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(300, 20));
        progressPanel.add(progressBar, gbc);

        getContentPane().add(progressPanel, BorderLayout.CENTER);
    }

    private void buildInputPanel() {
        inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel titleLabel = new JLabel("Selezione Algoritmo", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 14));
        inputPanel.add(titleLabel, gbc);

        gbc.gridy = 1; gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.LINE_END;
        inputPanel.add(new JLabel("Algoritmo:"), gbc);

        gbc.gridx = 1; gbc.anchor = GridBagConstraints.LINE_START;
        JComboBox<String> algoCombo = new JComboBox<>(new String[]{"ByteScrambler","HiddenXORFinder"});
        algoCombo.setPreferredSize(new Dimension(150, 30));
        algoCombo.setSelectedItem("ByteScrambler");
        inputPanel.add(algoCombo, gbc);

        JPanel operandsPanel = new JPanel();
        operandsPanel.setLayout(new BoxLayout(operandsPanel, BoxLayout.X_AXIS));
        operandsPanel.add(new JLabel("Numero operandi:"));
        SpinnerNumberModel model = new SpinnerNumberModel(2, 1, 10, 1);
        operandsSpinner = new JSpinner(model);
        operandsPanel.add(operandsSpinner);

        boolean showOperandsInitially = "ByteScrambler".equals(algoCombo.getSelectedItem());
        operandsPanel.setVisible(showOperandsInitially);

        algoCombo.addItemListener(e -> {
            boolean showOperands = "ByteScrambler".equals(e.getItem());
            operandsPanel.setVisible(showOperands);
            inputPanel.revalidate();
        });

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        inputPanel.add(operandsPanel, gbc);

        gbc.gridy = 3; gbc.anchor = GridBagConstraints.CENTER;
        JButton confirmButton = new JButton("Avvia Analisi");
        confirmButton.addActionListener(e -> handleAlgorithmSelection(algoCombo.getSelectedItem().toString()));
        inputPanel.add(confirmButton, gbc);
    }

    private void handleAlgorithmSelection(String algorithm) {
        this.selectedAlgorithm = algorithm;

        if ("ByteScrambler".equals(algorithm)) {
            try {
                maxOperands = (Integer) operandsSpinner.getValue();
                if (maxOperands > 2) {
                    int response = JOptionPane.showConfirmDialog(this,
                        "Attenzione: con "+maxOperands+" operandi il tempo di calcolo aumenterà esponenzialmente",
                        "Avviso", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);

                    if (response == JOptionPane.CANCEL_OPTION) {
                        return;
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Valore operandi non valido",
                    "Errore", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        remove(inputPanel);
        buildProgressPanel();
        add(progressPanel, BorderLayout.CENTER);
        revalidate();
        repaint();

        new Thread(this::startCalculation).start();
    }

    private void startCalculation() {
        try {
            if ("ByteScrambler".equals(selectedAlgorithm)) {
                candidateByResult = ByteScrambler.searchCandidates(dumps, maxOperands, (current, total) -> {
                    int percent = (int) ((current / (double) total) * 100);
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(percent);
                        progressLabel.setText(String.format("Calcolo in corso... %d%%", percent));
                    });
                });
            } else if ("HiddenXORFinder".equals(selectedAlgorithm)) {
                candidateByResult = HiddenXORFinder.searchCandidates(dumps, (current, total) -> {
                    int percent = (int) ((current / (double) total) * 100);
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(percent);
                        progressLabel.setText(String.format("Calcolo in corso... %d%%", percent));
                    });
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "Calculation interrupted", "Error", JOptionPane.ERROR_MESSAGE);
            });
            return;
        }
        SwingUtilities.invokeLater(this::buildMainUI);
    }

    private void buildMainUI() {
        remove(progressPanel);
        setResizable(true);
        setMinimumSize(new Dimension(700, 400));
        setSize(1100, 740);
        setLocationRelativeTo(null);

        tabbedPane = new JTabbedPane();
        dumpLabelMap.clear();

        for (int i = 0; i < dumps.size(); i++) {
            JPanel panel = new JPanel(new GridLayout(0, maxCols));
            int[] dump = dumps.get(i);
            Map<Integer, JLabel> byteMap = new HashMap<>();
            for (int j = 0; j < dump.length; j++) {
                JLabel label = createByteLabel(i, j, dump[j]);
                panel.add(label);
                byteMap.put(j, label);
            }
            dumpLabelMap.put(i, byteMap);
            tabbedPane.addTab("Dump " + (i + 1), panel);
        }
        tabbedPane.setSelectedIndex(0);
        updateHighlighting();
        tabbedPane.addChangeListener(e -> {
            int newDumpIndex = tabbedPane.getSelectedIndex();
            updateUIForDumpChange(newDumpIndex);
        });

        add(tabbedPane, BorderLayout.CENTER);
        initializeCandidateTable();
        setVisible(true);
    }

    private JLabel createByteLabel(int dumpIndex, int byteIndex, int value) {
        JLabel label = new JLabel(String.format("%02X", value), SwingConstants.CENTER);
        label.setOpaque(true);
        label.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleByteSelection(dumpIndex, byteIndex);
            }
        });
        return label;
    }

    private void updateHighlighting() {
        int currentDumpIndex = tabbedPane.getSelectedIndex();
        Map<Integer, JLabel> byteMap = dumpLabelMap.get(currentDumpIndex);
        if (byteMap == null) return;

        byteMap.forEach((byteIndex, label) -> {
            label.setBackground(Color.BLACK);
            label.setForeground(Color.WHITE);
            label.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            if (candidateByResult != null && candidateByResult.containsKey(byteIndex)) {
                label.setBackground(Color.YELLOW);
                label.setForeground(Color.BLACK);
            }
        });

        if (globalSelection.selectedByteIndex != null) {
            JLabel selectedLabel = byteMap.get(globalSelection.selectedByteIndex);
            if (selectedLabel != null) selectedLabel.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
        }

        if (globalSelection.selectedCandidate != null) {
            int[] operands = (int[]) globalSelection.selectedCandidate.get("operands");
            for (int i = 0; i < operands.length; i++) {
                if ("HiddenXORFinder".equals(selectedAlgorithm) && i == 1) {
                    continue;
                }
                JLabel lbl = byteMap.get(operands[i]);
                if (lbl != null) {
                    lbl.setBackground(Color.GREEN);
                    lbl.setForeground(Color.BLACK);
                }
            }
        }
    }

    private void handleByteSelection(int dumpIndex, int byteIndex) {
        globalSelection.selectedDumpIndex = dumpIndex;
        globalSelection.selectedByteIndex = byteIndex;
        globalSelection.selectedCandidate = null;

        updateTable();
        updateHighlighting();
        tabbedPane.setSelectedIndex(dumpIndex);
    }

    private void initializeCandidateTable() {
        tableModel = new DefaultTableModel(new String[]{"Operandi", "IDXRisultato", "Operazione"}, 0);
        candidateTable = new JTable(tableModel);
        candidateTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(candidateTable);
        scrollPane.setPreferredSize(new Dimension(400, 500));
        add(scrollPane, BorderLayout.EAST);

        // Custom renderer per colonne IDXRisultato e Operazione SOLO per HiddenXORFinder
        candidateTable.getColumnModel().getColumn(1).setCellRenderer(new HtmlCellRenderer());
        candidateTable.getColumnModel().getColumn(2).setCellRenderer(new HtmlCellRenderer());

        candidateTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = candidateTable.getSelectedRow();
                if (row >= 0) handleCandidateSelection(row);
            }
        });
    }

    // Custom cell renderer per la colonna "IDXRisultato" e "Operazione" con HTML, per colorare la chiave
    private class HtmlCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof String && ((String) value).startsWith("<html>")) {
                JLabel label = new JLabel((String) value);
                label.setOpaque(true);
                if (isSelected) {
                    label.setBackground(table.getSelectionBackground());
                    label.setForeground(table.getSelectionForeground());
                } else {
                    label.setBackground(table.getBackground());
                    label.setForeground(table.getForeground());
                }
                label.setFont(table.getFont());
                return label;
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    private void handleCandidateSelection(int row) {
        List<Map<String, Object>> candidates;
        if ("HiddenXORFinder".equals(selectedAlgorithm)) {
            candidates = candidateByResult.get(globalSelection.selectedByteIndex);
        } else {
            candidates = getCurrentCandidates();
        }
        if (candidates != null && row < candidates.size()) {
            globalSelection.selectedCandidate = candidates.get(row);
            updateHighlighting();
        }
    }

    private void updateUIForDumpChange(int newDumpIndex) {
        if (globalSelection.selectedByteIndex != null &&
            globalSelection.selectedByteIndex < dumps.get(newDumpIndex).length) {
            updateTable();
            updateHighlighting();
        } else {
            globalSelection.selectedByteIndex = null;
            globalSelection.selectedCandidate = null;
            tableModel.setRowCount(0);
        }
    }

    private void updateTable() {
        tableModel.setRowCount(0);

        if ("HiddenXORFinder".equals(selectedAlgorithm)) {
            List<Map<String, Object>> candidates = candidateByResult.get(globalSelection.selectedByteIndex);
            if (candidates == null) return;
            int currentDumpIndex = tabbedPane.getSelectedIndex();
            int[] currentDump = dumps.get(currentDumpIndex);
            for (Map<String, Object> cand : candidates) {
                int[] operands = (int[]) cand.get("operands");
                int resultIndex = (int) cand.get("result_index");
                List<Integer> results = (List<Integer>) cand.get("results");
                if (results == null) continue;

                // Calcola la chiave (valore)
                Integer keyVal = HiddenXORFinder.getKeyFromIndex(resultIndex);
                String chiaveHex = keyVal != null ? String.format("%02X", keyVal) : String.format("%02X", resultIndex);

                // Visualizza Operazione come: <byte1> XOR <byte2> = <chiave> (chiave in rosso)
                String byte1 = String.format("%02X", currentDump[operands[0]]);
                String byte2 = String.format("%02X", currentDump[operands[1]]);
                String operazioneStr = String.format("%s XOR %s = <span style='color:red;font-weight:bold;'>%s</span>", byte1, byte2, chiaveHex);

                // La chiave in IDXRisultato, in rosso
                tableModel.addRow(new Object[]{
                    Arrays.toString(operands),
                    "<html><span style='color:red;font-weight:bold;'>" + chiaveHex + "</span></html>",
                    "<html>" + operazioneStr + "</html>"
                });
            }
            restoreCandidateSelection();
            return;
        }

        // Altri algoritmi: ByteScrambler
        List<Map<String, Object>> candidates = getCurrentCandidates();
        if (candidates == null) return;
        int currentDumpIndex = tabbedPane.getSelectedIndex();
        int[] currentDump = dumps.get(currentDumpIndex);

        for (Map<String, Object> cand : candidates) {
            int[] operands = (int[]) cand.get("operands");
            List<Boolean> negations = (List<Boolean>) cand.get("negations");
            List<Boolean> reverses = (List<Boolean>) cand.get("reverses");
            int resultIndex = (int) cand.get("result_index");
            List<String> ops = (List<String>) cand.get("ops");
            List<Integer> results = (List<Integer>) cand.get("results");
            if (results == null) continue;
            StringBuilder opBuilder = new StringBuilder();
            for (int i = 0; i < operands.length; i++) {
                if (i > 0) opBuilder.append(" ").append(ops.isEmpty() ? "" : ops.get(i - 1)).append(" ");
                if (negations != null && i < negations.size() && negations.get(i)) {
                    opBuilder.append("!");
                }
                opBuilder.append(String.format("%02X", currentDump[operands[i]]));
            }
            opBuilder.append(" = ").append(String.format("%02X", results.get(currentDumpIndex)));
            tableModel.addRow(new Object[]{
                Arrays.toString(operands),
                resultIndex,
                opBuilder.toString()
            });
        }
        restoreCandidateSelection();
    }

    private void restoreCandidateSelection() {
        List<Map<String, Object>> candidates;
        if ("HiddenXORFinder".equals(selectedAlgorithm)) {
            candidates = candidateByResult.get(globalSelection.selectedByteIndex);
        } else {
            candidates = getCurrentCandidates();
        }
        if (globalSelection.selectedCandidate != null && candidates != null) {
            int row = findCandidateRow(candidates, globalSelection.selectedCandidate);
            if (row >= 0) candidateTable.setRowSelectionInterval(row, row);
        }
    }

    private int findCandidateRow(List<Map<String, Object>> list, Map<String, Object> target) {
        for (int i = 0; i < list.size(); i++) {
            if (candidateEquals(list.get(i), target)) return i;
        }
        return -1;
    }
    private boolean candidateEquals(Map<String, Object> a, Map<String, Object> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Arrays.equals((int[]) a.get("operands"), (int[]) b.get("operands"))
                && Objects.equals(a.get("ops"), b.get("ops"))
                && Objects.equals(a.get("result_index"), b.get("result_index"));
    }

    public void start() {
        setVisible(true);
    }
}