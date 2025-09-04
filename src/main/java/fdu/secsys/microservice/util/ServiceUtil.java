/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 14:42
 **/

package fdu.secsys.microservice.util;

import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.entity.Service;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static fdu.secsys.microservice.GatewayParser.routeConfigFiles;
import static fdu.secsys.microservice.GatewayParser.services;

public class ServiceUtil {
    private static final Logger logger = LogManager.getLogger(ServiceUtil.class);

    // get service name of the target jar
    public static void parseService(Path jar) {
        Set<String> keywordSet = Arrays.stream(Config.classpathKeywords).map(s -> s.replace(".", "/")).collect(Collectors.toSet());

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            String serviceName;
            List<String> classList;

            // parse pom.xml to get the service name
            serviceName = getServiceName(jarFile);
            logger.info("jarPath: {}", jar);
            logger.info("serviceName: {}", serviceName);
            if (services.stream().anyMatch(service -> service.getName().equals(serviceName))) {
                return;
            }

            // parse classes list
            classList = jarFile.stream()
                    .filter(entry -> !entry.isDirectory() && keywordSet.stream().anyMatch(keyword -> entry.getName().contains(keyword)))
                    .map(JarEntry::getName)
                    .collect(Collectors.toList());

            if (serviceName == null || classList.isEmpty()) {
                logger.error("[*] Empty service name in %s".formatted(jar.getFileName()));
                injectClassListIntoExistService(classList, jar.getFileName().toString());
                return;
            }

            // parse service route with the config location
            JarEntry routeEntry = getGatewayConfig(jarFile);
            if (routeEntry != null) {
                routeConfigFiles.put(jarFile, routeEntry);
            }

            services.add(new Service(serviceName, null, classList));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void injectClassListIntoExistService(List<String> classList, String jarName) {
        if (Arrays.stream(Config.classpathKeywords).anyMatch(k -> k.contains("tangyh"))) {
            model4LampCloud(classList, jarName);
        } else if (Arrays.stream(Config.classpathKeywords).anyMatch(k -> k.contains("paas"))) {
            model4PaasCloud(classList, jarName);
        }
    }

    private static void model4LampCloud(List<String> classList, String jarName) {
        String[] parts = jarName.split("-");
        if (parts.length < 2) {
            return;
        }
        String matchString = parts[0] + "-" + parts[1];
        List<Service> matchServices = services.stream()
                .filter(service -> service.getName().contains(matchString))
                .toList();
        matchServices.forEach(s -> s.getClassList().addAll(classList));
    }

    private static void model4PaasCloud(List<String> classList, String jarName) {
        String[] parts = jarName.split("-");
        if (parts.length < 3) {
            return;
        }
        String matchString = parts[0] + "-" + parts[1] + "-" + parts[2];
        List<Service> matchServices = services.stream()
                .filter(service -> service.getName().contains(matchString))
                .toList();
        matchServices.forEach(s -> s.getClassList().addAll(classList));
    }

    private static JarEntry getGatewayConfig(JarFile jarFile) {
        if (!Config.routeConfigFile.equals("")) {
            return jarFile.getJarEntry(Config.routeConfigFile);
        }
        List<JarEntry> routeConfigs = getConfigFile(jarFile).stream().filter(entry -> isGatewayConfigFile(jarFile, entry)).toList();
        if (routeConfigs.size() > 1) {
            logger.error("[-] Finding more than one gateway config file in %s".formatted(jarFile.getName()));
        } else if (routeConfigs.isEmpty()) {
            return null;
        }
        return routeConfigs.get(0);
    }

    private static String getServiceName(JarFile jarFile) {
        String serviceName = parseAppName(jarFile);
        if ((serviceName == null && Arrays.stream(Config.classpathKeywords).anyMatch(k -> k.contains("springblade")))
                || (serviceName != null && serviceName.contains("@pom"))) {
            return parseArtifactId(jarFile);
        }
        if (serviceName == null) {
            return parseArtifactId(jarFile);
        }
        return serviceName;
    }

    private static String parseAppName(JarFile jarFile) {
        // use snakeyaml to parse application.yaml
        List<JarEntry> configFiles = getConfigFile(jarFile).stream().filter(entry -> isAppConfigFile(jarFile, entry)).toList();
        return parseAppNameFromYaml(jarFile, configFiles);
    }

    private static List<JarEntry> getConfigFile(JarFile jarFile) {
        List<String> prefix = Arrays.asList("bootstrap", "application", "entry");
        List<String> suffix = Arrays.asList("yml", "yaml", "properties");
        return jarFile.stream()
                .filter(entry ->
                        prefix.stream().anyMatch(p -> entry.getName().contains(p))
                                && suffix.stream().anyMatch(s -> entry.getName().contains(s))
                ).toList();
    }

    private static String parseAppNameFromYaml(JarFile jarFile, List<JarEntry> routeConfigFiles) {
        for (JarEntry entry : routeConfigFiles) {
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                YamlUtil yamlUtil = new YamlUtil(inputStream);
                String prefix = "spring.application.name";
                String appName = yamlUtil.prefix(prefix).getString();

                if (appName != null && !appName.isEmpty()) {
                    return appName;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private static List<String> findAllPomXml(JarFile jarFile) {
        Enumeration<JarEntry> entries = jarFile.entries();
        List<String> pomXmlPaths = new ArrayList<>();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            if (!entry.isDirectory() && entryName.endsWith("pom.xml")) {
                pomXmlPaths.add(entryName);
            }
        }

        return pomXmlPaths;
    }

    private static String parseArtifactId(JarFile jarFile) {
        List<String> pomDirs = findAllPomXml(jarFile);
        if (pomDirs.isEmpty()) {
            logger.info("[*] no pom.xml in {}", jarFile.getName());
            return null;
        }
        if (pomDirs.size() > 1) {
            logger.info("[+] {} pom.xml in {}", pomDirs.size(), jarFile.getName());
        }
        List<String> serviceNames = new ArrayList<>();
        pomDirs.forEach(pomDir -> {
            JarEntry pomEntry = jarFile.getJarEntry(pomDir);
            try {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(jarFile.getInputStream(pomEntry));

                NodeList artifactIdNodes = doc.getElementsByTagName("artifactId");
                for (int i = 0; i < artifactIdNodes.getLength(); i++) {
                    Node node = artifactIdNodes.item(i);
                    if (!node.getParentNode().getNodeName().equals("parent")) {
                        serviceNames.add(node.getTextContent());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        if (!serviceNames.isEmpty()) {
            return serviceNames.get(0);
        }
        return null;
    }

    private static boolean isAppConfigFile(JarFile jarFile, JarEntry entry) {
        try (InputStreamReader inputStreamReader = new InputStreamReader(jarFile.getInputStream(entry));
             BufferedReader reader = new BufferedReader(inputStreamReader)) {
            return reader.lines().anyMatch(line -> line.contains("application:"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean isGatewayConfigFile(JarFile jarFile, JarEntry entry) {
        try (InputStreamReader inputStreamReader = new InputStreamReader(jarFile.getInputStream(entry));
             BufferedReader reader = new BufferedReader(inputStreamReader)) {
            List<String> lines = reader.lines().toList();
            // Check if any line contains 'zuul'
            boolean containsZuul = lines.stream().anyMatch(line -> line.contains("zuul"));
            // Check if any line contains 'spring' and 'routes'
            boolean containsSpringAndRoutes = lines.stream().anyMatch(line -> line.contains("spring")) &&
                    lines.stream().anyMatch(line -> line.contains("routes"));
            return containsZuul || containsSpringAndRoutes;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
