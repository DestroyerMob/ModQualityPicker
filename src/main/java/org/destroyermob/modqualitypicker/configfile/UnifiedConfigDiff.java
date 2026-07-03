package org.destroyermob.modqualitypicker.configfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnifiedConfigDiff {
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_EXACT_DIFF_CELLS = 4_000_000;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedConfigDiff() {
    }

    static List<String> create(String relativePath, List<String> baseLines, List<String> modifiedLines) {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(baseLines, "baseLines");
        Objects.requireNonNull(modifiedLines, "modifiedLines");

        if (baseLines.equals(modifiedLines)) {
            return List.of();
        }

        List<LineOp> ops = createLineOps(baseLines, modifiedLines);
        List<String> diff = new ArrayList<>();
        diff.add("--- a/" + relativePath);
        diff.add("+++ b/" + relativePath);

        List<Integer> changes = new ArrayList<>();
        for (int index = 0; index < ops.size(); index++) {
            if (ops.get(index).type() != OpType.EQUAL) {
                changes.add(index);
            }
        }

        int changeCursor = 0;
        while (changeCursor < changes.size()) {
            int firstChange = changes.get(changeCursor);
            int lastChange = firstChange;
            while (changeCursor + 1 < changes.size() && changes.get(changeCursor + 1) <= lastChange + (CONTEXT_LINES * 2) + 1) {
                changeCursor++;
                lastChange = changes.get(changeCursor);
            }

            int hunkStart = Math.max(0, firstChange - CONTEXT_LINES);
            int hunkEnd = Math.min(ops.size(), lastChange + CONTEXT_LINES + 1);
            appendHunk(diff, ops, hunkStart, hunkEnd);
            changeCursor++;
        }

        return diff;
    }

    static List<String> apply(List<String> baseLines, List<String> diffLines) throws IOException {
        Objects.requireNonNull(baseLines, "baseLines");
        Objects.requireNonNull(diffLines, "diffLines");

        if (diffLines.isEmpty()) {
            return List.copyOf(baseLines);
        }

        List<String> output = new ArrayList<>();
        int baseIndex = 0;
        int patchIndex = 0;
        while (patchIndex < diffLines.size() && (diffLines.get(patchIndex).startsWith("--- ") || diffLines.get(patchIndex).startsWith("+++ "))) {
            patchIndex++;
        }

        while (patchIndex < diffLines.size()) {
            String header = diffLines.get(patchIndex);
            Matcher matcher = HUNK_HEADER.matcher(header);
            if (!matcher.matches()) {
                throw new IOException("Invalid config diff hunk: " + header);
            }

            int oldStart = Integer.parseInt(matcher.group(1));
            int targetIndex = Math.max(0, oldStart - 1);
            if (targetIndex < baseIndex || targetIndex > baseLines.size()) {
                throw new IOException("Config diff hunk is out of range: " + header);
            }

            while (baseIndex < targetIndex) {
                output.add(baseLines.get(baseIndex));
                baseIndex++;
            }

            patchIndex++;
            while (patchIndex < diffLines.size() && !diffLines.get(patchIndex).startsWith("@@ ")) {
                String line = diffLines.get(patchIndex);
                if (line.isEmpty()) {
                    throw new IOException("Invalid empty config diff line");
                }

                char marker = line.charAt(0);
                String value = line.substring(1);
                if (marker == ' ') {
                    requireBaseLine(baseLines, baseIndex, value);
                    output.add(value);
                    baseIndex++;
                } else if (marker == '-') {
                    requireBaseLine(baseLines, baseIndex, value);
                    baseIndex++;
                } else if (marker == '+') {
                    output.add(value);
                } else if (marker != '\\') {
                    throw new IOException("Invalid config diff line: " + line);
                }
                patchIndex++;
            }
        }

        while (baseIndex < baseLines.size()) {
            output.add(baseLines.get(baseIndex));
            baseIndex++;
        }
        return output;
    }

    private static List<LineOp> createLineOps(List<String> baseLines, List<String> modifiedLines) {
        long cells = (long) (baseLines.size() + 1) * (modifiedLines.size() + 1);
        if (cells > MAX_EXACT_DIFF_CELLS) {
            return createPrefixSuffixOps(baseLines, modifiedLines);
        }

        int[][] lcs = new int[baseLines.size() + 1][modifiedLines.size() + 1];
        for (int baseIndex = baseLines.size() - 1; baseIndex >= 0; baseIndex--) {
            for (int modifiedIndex = modifiedLines.size() - 1; modifiedIndex >= 0; modifiedIndex--) {
                if (baseLines.get(baseIndex).equals(modifiedLines.get(modifiedIndex))) {
                    lcs[baseIndex][modifiedIndex] = lcs[baseIndex + 1][modifiedIndex + 1] + 1;
                } else {
                    lcs[baseIndex][modifiedIndex] = Math.max(lcs[baseIndex + 1][modifiedIndex], lcs[baseIndex][modifiedIndex + 1]);
                }
            }
        }

        List<LineOp> ops = new ArrayList<>();
        int baseIndex = 0;
        int modifiedIndex = 0;
        while (baseIndex < baseLines.size() || modifiedIndex < modifiedLines.size()) {
            if (baseIndex < baseLines.size()
                    && modifiedIndex < modifiedLines.size()
                    && baseLines.get(baseIndex).equals(modifiedLines.get(modifiedIndex))) {
                ops.add(new LineOp(OpType.EQUAL, baseLines.get(baseIndex)));
                baseIndex++;
                modifiedIndex++;
            } else if (modifiedIndex >= modifiedLines.size()
                    || (baseIndex < baseLines.size() && lcs[baseIndex + 1][modifiedIndex] >= lcs[baseIndex][modifiedIndex + 1])) {
                ops.add(new LineOp(OpType.DELETE, baseLines.get(baseIndex)));
                baseIndex++;
            } else {
                ops.add(new LineOp(OpType.INSERT, modifiedLines.get(modifiedIndex)));
                modifiedIndex++;
            }
        }
        return ops;
    }

    private static List<LineOp> createPrefixSuffixOps(List<String> baseLines, List<String> modifiedLines) {
        int prefix = 0;
        while (prefix < baseLines.size()
                && prefix < modifiedLines.size()
                && baseLines.get(prefix).equals(modifiedLines.get(prefix))) {
            prefix++;
        }

        int suffix = 0;
        while (suffix + prefix < baseLines.size()
                && suffix + prefix < modifiedLines.size()
                && baseLines.get(baseLines.size() - suffix - 1).equals(modifiedLines.get(modifiedLines.size() - suffix - 1))) {
            suffix++;
        }

        List<LineOp> ops = new ArrayList<>();
        for (int index = 0; index < prefix; index++) {
            ops.add(new LineOp(OpType.EQUAL, baseLines.get(index)));
        }
        for (int index = prefix; index < baseLines.size() - suffix; index++) {
            ops.add(new LineOp(OpType.DELETE, baseLines.get(index)));
        }
        for (int index = prefix; index < modifiedLines.size() - suffix; index++) {
            ops.add(new LineOp(OpType.INSERT, modifiedLines.get(index)));
        }
        for (int index = baseLines.size() - suffix; index < baseLines.size(); index++) {
            ops.add(new LineOp(OpType.EQUAL, baseLines.get(index)));
        }
        return ops;
    }

    private static void appendHunk(List<String> diff, List<LineOp> ops, int hunkStart, int hunkEnd) {
        int oldLinesBefore = 0;
        int newLinesBefore = 0;
        for (int index = 0; index < hunkStart; index++) {
            LineOp op = ops.get(index);
            if (op.type() != OpType.INSERT) {
                oldLinesBefore++;
            }
            if (op.type() != OpType.DELETE) {
                newLinesBefore++;
            }
        }

        int oldCount = 0;
        int newCount = 0;
        for (int index = hunkStart; index < hunkEnd; index++) {
            LineOp op = ops.get(index);
            if (op.type() != OpType.INSERT) {
                oldCount++;
            }
            if (op.type() != OpType.DELETE) {
                newCount++;
            }
        }

        diff.add("@@ -" + (oldLinesBefore + 1) + "," + oldCount + " +" + (newLinesBefore + 1) + "," + newCount + " @@");
        for (int index = hunkStart; index < hunkEnd; index++) {
            LineOp op = ops.get(index);
            char marker = switch (op.type()) {
                case EQUAL -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
            diff.add(marker + op.line());
        }
    }

    private static void requireBaseLine(List<String> baseLines, int baseIndex, String expected) throws IOException {
        if (baseIndex >= baseLines.size() || !baseLines.get(baseIndex).equals(expected)) {
            throw new IOException("Config diff does not apply cleanly");
        }
    }

    private enum OpType {
        EQUAL,
        DELETE,
        INSERT
    }

    private record LineOp(OpType type, String line) {
    }
}
