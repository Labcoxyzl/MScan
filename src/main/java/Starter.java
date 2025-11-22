import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.GatewayParser;
import fdu.secsys.microservice.util.JarUtil;
import fdu.secsys.microservice.util.ServiceUtil;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.Main;
import pascal.taie.util.Timer;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class Starter {

    private static final Logger logger = LogManager.getLogger(Starter.class);

    public static void main(String[] args) throws IOException {
        // Parse command-line arguments
        StarterOptions options = new StarterOptions();
        CommandLine cmd = new CommandLine(options);

        try {
            cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            cmd.getErr().println(e.getMessage());
            cmd.usage(cmd.getErr());
            System.exit(1);
            return;
        }

        // Handle help request
        if (cmd.isUsageHelpRequested()) {
            cmd.usage(cmd.getOut());
            System.exit(0);
            return;
        }

        // Validate options
        try {
            options.validate();
        } catch (IllegalArgumentException e) {
            cmd.getErr().println("Validation error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Populate Config from parsed options
        populateConfigFromOptions(options);

        // Run analysis with timer
        Timer.runAndCount(() -> {
            try {
                parseJar(Config.jarPath);
                GatewayParser.mapServiceRoute();
                Main.main("--options-file", Config.optionsFile);
            } catch (Exception e) {
                logger.error("[!] {}: {}", Config.name, e);
                e.printStackTrace();
            }
        }, Config.name);
    }

    /**
     * Populate static Config fields from parsed CLI options
     * Package-private for testing
     */
    static void populateConfigFromOptions(StarterOptions options) {
        Config.name = options.getName();
        Config.classpathKeywords = options.getClasspathKeywords();
        Config.jarPath = options.getJarPath();
        Config.targetPath = options.getTargetPath(); // includes default logic
        Config.reuse = options.isReuse();
        Config.optionsFile = options.getOptionsFile();

        logger.info("Configuration loaded:");
        logger.info("  Name: {}", Config.name);
        logger.info("  Classpath Keywords: {}", String.join(", ", Config.classpathKeywords));
        logger.info("  JAR Path: {}", Config.jarPath);
        logger.info("  Target Path: {}", Config.targetPath);
        logger.info("  Reuse: {}", Config.reuse);
        logger.info("  Options File: {}", Config.optionsFile);
    }

    public static void parseJar(String jarDir) throws IOException {
        boolean reuse = Config.reuse;
        Path targetPath = Path.of(Config.targetPath);
        if (!reuse) {
            logger.info("[+] removing cache");
            try (Stream<Path> paths = Files.list(targetPath)) {
                paths.forEach(path -> {
                    try {
                        if (path.toFile().isDirectory()) {
                            FileUtils.deleteDirectory(path.toFile());
                        } else {
                            FileUtils.delete(path.toFile());
                        }
                    } catch (Exception e) {
                        logger.warn(e);
                    }
                });
            } catch (Exception e) {
                logger.warn(e);
            }
        }
        logger.info("[+] processing jars");
        List<Path> jars = JarUtil.getJarFiles(Path.of(jarDir));
        for (Path jar : jars) {
            // init service discovery
            ServiceUtil.parseService(jar);
            // unzip jars to extract classes and lib
            if (!reuse) {
                JarUtil.extractJar(jar, targetPath);
            }
        }
        for (Path jar : jars) {
            ServiceUtil.parseService(jar);
        }
        if (!reuse) {
            logger.info("[+] moving mapper");
            JarUtil.moveMapper(targetPath);
            logger.info("[+] moving classes");
            JarUtil.unsafeMoveClasses(targetPath);
        }
        logger.info("[+] starter end");
    }
}
