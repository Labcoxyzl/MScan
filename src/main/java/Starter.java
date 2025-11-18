import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.GatewayParser;
import fdu.secsys.microservice.util.JarUtil;
import fdu.secsys.microservice.util.ServiceUtil;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.Main;
import pascal.taie.util.Timer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class Starter {

    private static final Logger logger = LogManager.getLogger(Starter.class);


    public static void main(String[] args) throws IOException {
        Timer.runAndCount(() -> {
            Config.name = "youlai-mall";
            Config.classpathKeywords = new String[]{"youlai"};
            Config.jarPath = "../../cloud/dataset/youlai-mall";
            Config.targetPath = "../../cloud/tmp/youlai-mall";
            Config.reuse = false;
            Config.optionsFile = "src/main/resources/options.yaml";
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
