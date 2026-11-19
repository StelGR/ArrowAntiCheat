package me.arrow.files.commentedfiles;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommentedFileConfigurationHelper {

    JavaPlugin plugin;

    /**
     * Manage custom configurations and files
     *
     * @param plugin The JavaPlugin the configuration is for
     */
    public CommentedFileConfigurationHelper(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get new configuration
     *
     * @param file - Path to file
     * @return - New SimpleConfig
     */
    public CommentedFileConfiguration getNewConfig(File file) {
        File dataFolder = this.plugin != null ? this.plugin.getDataFolder() : me.arrow.Arrow.getInstance().getDataFolder();
        if (dataFolder != null && !dataFolder.exists())
            dataFolder.mkdir();

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfigurationHelper", "caught-exception", e);
            }
        }

        return new CommentedFileConfiguration(this.getConfigContent(file), file, this.getCommentsNum(file), this.plugin);
    }

    /**
     * Read file and make comments SnakeYAML friendly
     *
     * @param file - Path to file
     * @return - File as Input Stream
     */
    public Reader getConfigContent(File file) {
        if (!file.exists())
            return new InputStreamReader(new ByteArrayInputStream(new byte[0]));

        try {
            int commentNum = 0;
            String pluginName = this.getPluginName();
            StringBuilder whole = new StringBuilder();
            List<String> lines = Files.readAllLines(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
            int firstContentLine = nextContentLine(lines, 0);

            for (int i = 0; i < lines.size(); i++) {
                String currentLine = lines.get(i);
                if (currentLine.trim().startsWith("#")) {
                    int commentBlockEnd = i + 1;
                    while (commentBlockEnd < lines.size()
                            && lines.get(commentBlockEnd).trim().startsWith("#")) {
                        commentBlockEnd++;
                    }

                    boolean leadingHeader = i < firstContentLine;
                    if (!leadingHeader && !hasYamlValueAfter(lines, commentBlockEnd)) {
                        // Ignore orphan comment blocks left behind by older saves. If we
                        // re-imported them as metadata they would be copied forward on
                        // every reload and keep bloating the user's file.
                        i = commentBlockEnd - 1;
                        continue;
                    }

                    int marker = currentLine.indexOf('#');
                    int nextContentLine = nextContentLine(lines, i + 1);
                    String indentation = nextContentLine < 0
                            ? ""
                            : lines.get(nextContentLine).substring(0, leadingWhitespace(lines.get(nextContentLine)));

                    // Synthetic comment keys must be siblings of the next YAML value.
                    // Reusing the source comment indentation can reopen a nested section
                    // after a root key and make an otherwise valid config unreadable.

                    String commentText = currentLine.substring(marker + 1).replace("'", "''");
                    whole.append(indentation)
                            .append(pluginName).append("_COMMENT_").append(commentNum++)
                            .append(": '").append(commentText).append("'\n");
                } else {
                    whole.append(currentLine).append('\n');
                }
            }

            return new StringReader(whole.toString());
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfigurationHelper", "caught-exception", e);
            return null;
        }
    }

    /**
     * Get comments from file
     *
     * @param file - File
     * @return - Comments number
     */
    private int getCommentsNum(File file) {
        if (!file.exists())
            return 0;

        try {
            int comments = 0;
            String currentLine;

            BufferedReader reader = Files.newBufferedReader(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);

            while ((currentLine = reader.readLine()) != null)
                if (currentLine.trim().startsWith("#"))
                    comments++;

            reader.close();
            return comments;
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfigurationHelper", "caught-exception", e);
            return 0;
        }
    }

    public boolean needsFormatting(File file) {
        if (file == null || !file.exists()) {
            return false;
        }

        try {
            String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (Pattern.compile("(?:\\r?\\n[ \\t]*){3,}").matcher(contents).find()) {
                return true;
            }

            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int firstContentLine = nextContentLine(lines, 0);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("#")) {
                    int commentBlockEnd = i + 1;
                    while (commentBlockEnd < lines.size()
                            && lines.get(commentBlockEnd).trim().startsWith("#")) {
                        commentBlockEnd++;
                    }
                    if (i >= firstContentLine && !hasYamlValueAfter(lines, commentBlockEnd)) {
                        return true;
                    }
                }

                int marker = line.indexOf('#');
                if (marker >= 0 && previousContentLine(lines, i - 1) >= 0
                        && line.substring(0, marker).trim().isEmpty()
                        && nextContentIndent(lines, i + 1) > marker) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfigurationHelper", "caught-exception", e);
            return false;
        }
    }

    private boolean hasYamlValueAfter(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return !trimmed.startsWith("#");
        }
        return false;
    }

    private int nextContentIndent(List<String> lines, int start) {
        int lineIndex = nextContentLine(lines, start);
        return lineIndex < 0 ? 0 : leadingWhitespace(lines.get(lineIndex));
    }

    private int nextContentLine(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return i;
            }
        }
        return -1;
    }

    private int previousContentLine(List<String> lines, int start) {
        for (int i = start; i >= 0; i--) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return i;
            }
        }
        return -1;
    }

    private int leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private String prepareConfigString(String configString) {
        boolean lastLine = false;

        String[] lines = configString.split("\n");
        StringBuilder config = new StringBuilder();

        for (String line : lines) {
            if (line.trim().startsWith(this.getPluginName() + "_COMMENT")) {
                int whitespaceIndex = line.indexOf(line.trim());
                String comment = line.substring(0, whitespaceIndex) + "#" + line.substring(line.indexOf(":") + 3, line.length() - 1);

                String normalComment;
                if (comment.trim().startsWith("#'")) {
                    normalComment = comment.substring(0, comment.length() - 1).replaceFirst("#'", "# ");
                } else {
                    normalComment = comment;
                }

                normalComment = normalComment.replaceAll("''", "'");

                if (!lastLine) {
                    config.append(normalComment).append("\n");
                } else {
                    config.append("\n").append(normalComment).append("\n");
                }

                lastLine = false;
            } else {
                config.append(line).append("\n");
                lastLine = true;
            }
        }

        return config.toString();
    }

    /**
     * Saves configuration to file
     *
     * @param configString - Config string
     * @param file         - Config file
     * @param compactLines - If lines should forcefully be separated by only one newline character
     */
    public void saveConfig(String configString, File file, boolean compactLines) {
        String configuration = this.prepareConfigString(configString)
                .replaceAll("(?m)\\n(?:[ \\t]*\\n)+", "\n\n");

        // Apply post-processing to config string to make it pretty
        StringBuilder stringBuilder = new StringBuilder();
        try (Scanner scanner = new Scanner(configuration)) {
            boolean lastLineHadContent = false;
            int lastCommentSpacing = -1;
            int lastLineSpacing = -1;
            boolean forceCompact = false;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                boolean lineHadContent = false;
                boolean lineWasComment = false;
                int commentSpacing = -1;
                int lineSpacing = line.indexOf(line.trim());

                if (line.trim().startsWith("#")) {
                    lineWasComment = true;
                    String trimmed = line.trim().replaceFirst("#", "");
                    commentSpacing = trimmed.indexOf(trimmed.trim());
                } else if (!line.trim().isEmpty()) {
                    lineHadContent = true;

                    if (line.trim().startsWith("-"))
                        forceCompact = true;
                }

                if (!compactLines && !forceCompact && (
                        (lastLineSpacing != -1 && lineSpacing != lastLineSpacing)
                                || (commentSpacing != -1 && commentSpacing < lastCommentSpacing)
                                || (lastLineHadContent && lineHadContent)
                                || (lineWasComment && lastLineHadContent))
                        && !(lastLineHadContent && !lineWasComment)) {
                    stringBuilder.append('\n');
                }

                stringBuilder.append(line).append('\n');

                lastLineHadContent = lineHadContent;
                lastCommentSpacing = commentSpacing;
                lastLineSpacing = lineSpacing;
                forceCompact = false;
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8)) {
            String formatted = stringBuilder.toString()
                    .replaceAll("(?:\\r?\\n[ \\t]*){3,}", "\n\n");
            writer.write(formatted);
            writer.flush();
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfigurationHelper", "caught-exception", e);
        }
    }

    public String getPluginName() {
        return (this.plugin != null && this.plugin.getDescription() != null) ? this.plugin.getDescription().getName() : "Arrow";
    }

}
