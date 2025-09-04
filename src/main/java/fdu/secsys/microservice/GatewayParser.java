/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 18:17
 **/

package fdu.secsys.microservice;

import fdu.secsys.microservice.entity.Service;
import fdu.secsys.microservice.util.YamlUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * parse gateway to get the route rules
 * support for spring-cloud-gateway, zuul, etc.
 */
public class GatewayParser {

    private static final Logger logger = LogManager.getLogger(GatewayParser.class);

    public static Map<JarFile, JarEntry> routeConfigFiles = new HashMap<>();

    public static List<Service> services = new ArrayList<>();

    public static void mapServiceRoute() {
        // there should be only one route config file in project
        if (routeConfigFiles.size() > 1) {
            String allEntries = routeConfigFiles.values().stream()
                    .map(JarEntry::getName)
                    .collect(Collectors.joining(", "));
            logger.error("[-] More than one route configuration file found: %s".formatted(allEntries));
        }

        routeConfigFiles.forEach((jar, routeConfig) -> {
            try (JarFile jarFile = new JarFile(jar.getName())) {
                InputStream input = jarFile.getInputStream(routeConfig);
                Map<String, List<String>> routes = getRoutes(input);
                if (routes == null) {
                    logger.error("[-] Routes configuration not found or is invalid.");
                    return;
                }
                routes.forEach(GatewayParser::mapServiceRoute);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void mapServiceRoute(String serviceName, List<String> extractedRoutes) {
        Optional<Service> optionalService = services.stream()
                .filter(s -> s.getName().equalsIgnoreCase(serviceName))
                .findFirst();

        optionalService.ifPresentOrElse(service -> {
            // set route for service
            List<String> serviceRoutes = service.getRoute();
            if (serviceRoutes == null) {
                serviceRoutes = new ArrayList<>();
                service.setRoute(serviceRoutes);
            }
            serviceRoutes.addAll(extractedRoutes);
        }, () -> logger.error("[-] Service %s not found!".formatted(serviceName)));
    }


    private static Map<String, List<String>> getRoutes(InputStream yamlInputStream) {
        YamlUtil yamlUtil = new YamlUtil(yamlInputStream);
        Map<String, Object> yamlMap = yamlUtil.getMap();

        if (yamlMap.containsKey("zuul")) {
            return getRoutesFromZuul(yamlUtil);
        } else if (yamlMap.containsKey("spring")) {
            return getRoutesFromSpringCloud(yamlUtil);
        }
        return null;
    }


    private static Map<String, List<String>> getRoutesFromZuul(YamlUtil yamlUtil) {
        Map<String, List<String>> extractedRoutes = new HashMap<>();

        Map<String, Object> zuulRoutes = yamlUtil.prefix("zuul.routes").getMap();

        if (zuulRoutes != null) {
            zuulRoutes.forEach((routeId, routeDetails) -> {
                if (routeDetails instanceof Map) {
                    Map<String, Object> details = (Map<String, Object>) routeDetails;
                    String path = (String) details.get("path");
                    String serviceId = (String) details.get("service-id");
                    if (serviceId == null) {
                        serviceId = (String) details.get("serviceId");
                    }
                    boolean stripPrefix = details.getOrDefault("strip-prefix", true).equals(true);

                    if (stripPrefix && path != null) {
                        path = removeFirstLevel(path);
                    }

                    extractedRoutes.computeIfAbsent(serviceId, k -> new ArrayList<>()).add(path);
                }
            });
        }
        return extractedRoutes;
    }

    private static Map<String, List<String>> getRoutesFromSpringCloud(YamlUtil yamlUtil) {
        Map<String, List<String>> extractedRoutes = new HashMap<>();

        List<Map<String, Object>> routes = yamlUtil.prefix("spring.cloud.gateway.routes").getList();

        if (routes == null) {
            logger.error("[-] Config file not found or is invalid for spring cloud!");
            return null;
        }

        for (Map<String, Object> route : routes) {
            String uri = (String) route.get("uri");
            String serviceName = extractServiceNameFromUri(uri);

            // spring-cloud-gateway
            List<String> predicates = (List<String>) route.get("predicates");
            List<String> filters = (List<String>) route.get("filters");
            List<String> paths = new ArrayList<>();
            if (predicates != null) {
                for (String predicate : predicates) {
                    if (predicate.startsWith("Path=")) {
                        String path = predicate.substring(predicate.indexOf("=") + 1);
                        if (isStrip(filters)) {
                            path = removeFirstLevel(path);
                        }
                        paths.add(path);
                    }
                }
            }
            extractedRoutes.computeIfAbsent(serviceName, k -> new ArrayList<>()).addAll(paths);
        }
        return extractedRoutes;
    }

    private static String extractServiceNameFromUri(String uriString) {
        try {
            URI uri = new URI(uriString);
            String serviceName = uri.getHost();
            return serviceName != null ? serviceName : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isStrip(List<String> filters) {
        if (filters == null) {
            return true;
        }
        for (String filter : filters) {
            if (filter.equals("StripPrefix=1")) {
                return true;
            }
        }
        return false;
    }

    public static String removeFirstLevel(String input) {
        String[] parts = input.split("/");
        StringBuilder resultBuilder = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            resultBuilder.append("/").append(parts[i]);
        }
        return resultBuilder.toString();
    }
}
