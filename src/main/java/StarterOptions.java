import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;

/**
 * Command-line options for MScan Starter
 */
@Command(
    name = "mscan",
    description = "MScan: Detecting Taint-Style Vulnerabilities in Microservice-Structured Web Applications"
)
public class StarterOptions {

    @Option(
        names = {"-n", "--name"},
        description = "Project name (used for naming output and temp directories)",
        required = true
    )
    private String name;

    @Option(
        names = {"-h", "--help"},
        usageHelp = true,
        description = "Display this help message"
    )
    private boolean helpRequested;

    @Option(
        names = {"-k", "--classpath-keywords"},
        description = "Comma-separated package keywords to filter application classes (e.g., 'com.piggymetrics.,com.example.')",
        split = ","
    )
    private String[] classpathKeywords = new String[0];

    @Option(
        names = {"-j", "--jar-path"},
        description = "Path to directory containing JAR files to analyze",
        required = true
    )
    private String jarPath;

    @Option(
        names = {"-t", "--target-path"},
        description = "Target path for extracted files and analysis output (default: /tmp/<project-name>)"
    )
    private String targetPath;

    @Option(
        names = {"-r", "--reuse"},
        description = "Reuse existing extracted files instead of re-extracting (default: false)"
    )
    private boolean reuse = false;

    @Option(
        names = {"-o", "--options-file"},
        description = "Path to Tai-e options file (default: src/main/resources/options.yml)"
    )
    private String optionsFile = "src/main/resources/options.yml";

    // Getters
    public String getName() {
        return name;
    }

    public String[] getClasspathKeywords() {
        return classpathKeywords;
    }

    public String getJarPath() {
        return jarPath;
    }

    public String getTargetPath() {
        // If not specified, default to /tmp/<project-name>
        return targetPath != null ? targetPath : "/tmp/" + name;
    }

    public boolean isReuse() {
        return reuse;
    }

    public String getOptionsFile() {
        return optionsFile;
    }

    // Setters for testing
    void setName(String name) {
        this.name = name;
    }

    void setClasspathKeywords(String[] classpathKeywords) {
        this.classpathKeywords = classpathKeywords;
    }

    void setJarPath(String jarPath) {
        this.jarPath = jarPath;
    }

    void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    void setReuse(boolean reuse) {
        this.reuse = reuse;
    }

    void setOptionsFile(String optionsFile) {
        this.optionsFile = optionsFile;
    }

    /**
     * Validate the parsed options
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        // Validate JAR path exists
        File jarPathFile = new File(jarPath);
        if (!jarPathFile.exists()) {
            throw new IllegalArgumentException(
                String.format("JAR path does not exist: %s", jarPath)
            );
        }
        if (!jarPathFile.isDirectory()) {
            throw new IllegalArgumentException(
                String.format("JAR path is not a directory: %s", jarPath)
            );
        }

        // Validate options file exists
        File optionsFileObj = new File(optionsFile);
        if (!optionsFileObj.exists()) {
            throw new IllegalArgumentException(
                String.format("Options file does not exist: %s", optionsFile)
            );
        }

        // Validate classpath keywords are not empty
        if (classpathKeywords.length > 0) {
            for (String keyword : classpathKeywords) {
                if (keyword == null || keyword.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                        "Classpath keywords cannot be empty"
                    );
                }
            }
        }

        // Validate name is not empty
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Project name cannot be empty");
        }
    }
}
