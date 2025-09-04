/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 16:31
 **/

package fdu.secsys.microservice.util;

import fdu.secsys.microservice.Config;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JarUtil {

    static Logger logger = LogManager.getLogger(JarUtil.class);

    static List<String> tlds = new ArrayList<>(List.of("com", "cn", "org", "io"));

    public static List<Path> getJarFiles(Path path) {
        List<Path> jarFiles = new ArrayList<>();
        try {
            Files.walk(path)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jarFiles::add);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return jarFiles;
    }

    public static void extractJar(Path jarFilePath, Path destDir) throws IOException {
        File jarFile = jarFilePath.toFile();
        try (JarFile file = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    if (entry.getName().contains("..")) {
                        throw new IOException("Illegal character .. in entry name %s".formatted(entry.getName()));
                    }
                    Path entryFilePath = destDir.resolve(entry.getName());
                    File entryFile = entryFilePath.toFile();
                    File entryParent = entryFile.getParentFile();
                    if (!entryParent.exists()) {
                        entryParent.mkdirs();
                    }
                    try (InputStream inputStream = file.getInputStream(entry);
                         FileOutputStream outputStream = new FileOutputStream(entryFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
        }
    }

    public static void moveMapper(Path targetPath) {
        try {
            Path mapperPath = targetPath.resolve("mapper");
            File mapperDir = mapperPath.toFile();
            mapperDir.mkdir();
            File file = targetPath.toFile();
            if (file.isDirectory()) {
                try (Stream<Path> walk = Files.walk(targetPath)) {
                    walk.filter(path -> path.toFile().getName().endsWith("Dao.xml") || path.toFile().getName().endsWith("Mapper.xml"))
                            .forEach(path -> {
                                try {
                                    Path newPath = mapperPath.resolve(path.toFile().getName());
                                    String filename = path.toFile().getName();
                                    StringBuilder prefix = new StringBuilder();
                                    int count = 0;
                                    while (newPath.toFile().exists() && count < 2) {
                                        prefix.append("_");
                                        count++;
                                        newPath = mapperPath.resolve(prefix + filename);
                                    }
                                    Files.copy(path, newPath);
                                } catch (FileAlreadyExistsException e) {

                                } catch (Exception e) {
                                    logger.warn(e);
                                }
                            });
                } catch (IOException e) {
                    logger.warn("Failed to walk directory {} due to {}", targetPath, e);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void unsafeMoveClasses(Path targetPath) {
        List<String> root = tlds;
        Path classesPath = targetPath.resolve("BOOT-INF/classes");
        if (!classesPath.toFile().exists()) {
            try {
                classesPath.toFile().mkdirs();
            } catch (Exception e) {
                throw new RuntimeException("BOOT-INF/classes not exists");
            }
        }
        root.stream().map(p -> {
            try {
                Path path = targetPath.resolve(p);
                File file = path.toFile();
                if (file.isDirectory()) {
                    return p;
                }
            } catch (Exception e) {
                logger.warn(e);
                return null;
            }
            return null;
        }).filter(Objects::nonNull).forEach(dir -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                String source = targetPath.resolve(dir).toFile().getCanonicalPath();
                String dest = classesPath.resolve(dir).toFile().getCanonicalPath();
                String xCopyDest = dest.endsWith(File.separator) ? dest : dest + File.separator;
                String copyDest = classesPath.toFile().getCanonicalPath().endsWith(File.separator) ? classesPath.toFile().getCanonicalPath() : classesPath.toFile().getCanonicalPath() + File.separator;
                Pattern pattern = Pattern.compile("[$|`<>]");
                if (pattern.matcher(source).find() || pattern.matcher(xCopyDest).find() || pattern.matcher(copyDest).find()) {
                    throw new RuntimeException("dangerous char found in path");
                }
                if (os.contains("win")) {
                    ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "xcopy /s /e /y %s %s".formatted(source, xCopyDest));
                    Process process = processBuilder.start();
                    drain(process);
                    process.waitFor();
                } else {
                    ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", "cp -r %s %s".formatted(source, copyDest));
                    Process process = processBuilder.start();
                    drain(process);
                    process.waitFor();
                }
            } catch (Exception e) {
                logger.warn(e);
            }
        });
    }

    @Deprecated
    public static void unsafeMoveMapper(Path targetPath) {
        try {
            Path mapperPath = targetPath.resolve("mapper");
            File mapperDir = mapperPath.toFile();
            mapperDir.mkdir();
            String os = System.getProperty("os.name").toLowerCase();
            String source = targetPath.toFile().getCanonicalPath().replace("\\", "/");
            String dest = mapperDir.getCanonicalPath().replace("\\", "/");
            if (os.contains("win")) {
                ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "bash", "-c", "find %s -name \"*Mapper.xml\" -exec cp -r {} %s \\;".formatted(source, dest));
                Process process = processBuilder.start();
                drain(process);
                process.waitFor();
                processBuilder = new ProcessBuilder("cmd", "/c", "bash", "-c", "find %s -name \"*Dao.xml\" -exec cp -r {} %s \\;".formatted(source, dest));
                process = processBuilder.start();
                drain(process);
                process.waitFor();
            } else {
                ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", "find %s -name \"*Mapper.xml\" -exec cp -r {} %s \\;".formatted(source, dest));
                Process process = processBuilder.start();
                drain(process);
                process.waitFor();
                processBuilder = new ProcessBuilder("bash", "-c", "find %s -name \"*Dao.xml\" -exec cp -r {} %s \\;".formatted(source, dest));
                process = processBuilder.start();
                drain(process);
                process.waitFor();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<String> collectClassPath() {
        List<String> list = new ArrayList<>(List.of("BOOT-INF/lib"));
        Path targetPath = Path.of(Config.targetPath);
        list = list.stream().map(p -> {
            try {
                Path path = targetPath.resolve(p);
                File file = path.toFile();
                if (file.isDirectory()) {
                    return path.toFile().getCanonicalPath();
                }
            } catch (Exception e) {
                logger.warn(e);
                return null;
            }
            return null;
        }).filter(Objects::nonNull).toList();
        return list;
    }

    public static List<String> collectAppClassPath() {
        List<String> list = new ArrayList<>(List.of("BOOT-INF/classes"));
        Path targetPath = Path.of(Config.targetPath);
        list = list.stream().map(p -> {
            try {
                Path path = targetPath.resolve(p);
                File file = path.toFile();
                if (file.isDirectory()) {
                    return path.toFile().getCanonicalPath();
                }
            } catch (Exception e) {
                logger.warn(e);
                return null;
            }
            return null;
        }).filter(Objects::nonNull).toList();
        return list;
    }

    public static void drain(Process process) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while ((reader.readLine()) != null) {
                }
            } catch (Exception e) {
                logger.warn(e);
            }
        }).start();
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((reader.readLine()) != null) {
                }
            } catch (Exception e) {
                logger.warn(e);
            }
        }).start();
    }

}
