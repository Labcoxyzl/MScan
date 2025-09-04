package fdu.secsys.microservice.plugin.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.GatewayParser;
import fdu.secsys.microservice.entity.Endpoint;
import fdu.secsys.microservice.entity.Service;
import fdu.secsys.microservice.util.FeignUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.plugin.taint.container.spring.Utils;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.classes.ClassHierarchy;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EndpointHandler {

    private static final Logger logger = LogManager.getLogger(EndpointHandler.class);

    private final List<Endpoint> endpoints;

    private final ClassHierarchy hierarchy;

    private static final boolean useLLM = true;

    public EndpointHandler(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
        this.endpoints = new ArrayList<>();
    }

    public List<Endpoint> getEndpoints() {

        if(!endpoints.isEmpty()) {
            return endpoints;
        }
        List<String> externalEntries = new ArrayList<>();
        List<String> internalEntries = new ArrayList<>();
        boolean error = false;
        if (useLLM) {
            try {
                InputStream gatewayInputStream = ClassLoader.getSystemClassLoader().getResourceAsStream("entry/%s.json".formatted(Config.name));
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> result = mapper.readValue(gatewayInputStream, Map.class);
                externalEntries = (List<String>) result.get("external_entries");
                internalEntries = (List<String>) result.get("internal_entries");
            } catch (Exception e) {
                error = true;
            }
        }

        // 1. the endpoint method should contain @\w+Mapping annotations
        // 2. the endpoint should
        boolean finalError = error;
        List<String> finalExternalEntries = externalEntries;
        List<String> finalInternalEntries = internalEntries;
        hierarchy.applicationClasses().forEach(jClass -> {
            String prefixRoute = FeignUtil.getFirstMapping(jClass).orElse("");
            jClass.getDeclaredMethods().stream().filter(m -> Utils.isController(m) || Utils.isJavaxRS(m)).forEach(m -> {
                boolean isExposed = false;
                String name = m.getName();
                String clazz = jClass.getName();

                // get the path of the endpoint
                String mappingRoute = FeignUtil.getFirstMapping(m).orElse("");
                String route = routeHandler(FeignUtil.joinMapping(prefixRoute, mappingRoute), clazz);

                if(useLLM && !finalError) {
                    if(finalExternalEntries.stream().anyMatch(rule -> {
                        try {
                            Pattern routePattern = Pattern.compile(processRule(rule));
                            return routePattern.matcher(route).find();
                        } catch (Exception e) {
                            return false;
                        }
                    })) {
                        Endpoint endpoint = new Endpoint(name, clazz, route, m, true, new ArrayList<>(), new ArrayList<>());
                        endpoints.add(endpoint);
                    } else if(finalInternalEntries.stream().anyMatch(rule -> {
                        try {
                            Pattern routePattern = Pattern.compile(processRule(rule));
                            return routePattern.matcher(route).find();
                        } catch (Exception e) {
                            return false;
                        }
                    })) {
                        Endpoint endpoint = new Endpoint(name, clazz, route, m, false, new ArrayList<>(), new ArrayList<>());
                        endpoints.add(endpoint);
                    } else {
                        Endpoint endpoint = new Endpoint(name, clazz, route, m, true, new ArrayList<>(), new ArrayList<>());
                        endpoints.add(endpoint);
                    }
                } else {
                    // check whether the path is exposed
                    List<Service> relevantServices = matchService(clazz);
                    // if service name of feign is null, check annotation
                    if (relevantServices.isEmpty() && FeignUtil.isFeignClient(jClass)) {
                        Optional<String> optional = jClass.getAnnotations().stream()
                                .map(anno -> anno.getElement("name"))
                                .filter(Objects::nonNull)
                                .findFirst()
                                .map(Object::toString)
                                .map(value -> value.substring(1, value.length() - 1));
                        String targetService = optional.orElse("");
                        if (!targetService.isEmpty()) {
                            relevantServices = GatewayParser.services.stream()
                                    .filter(service -> service.getName().equals(targetService))
                                    .collect(Collectors.toList());
                        }
                    }
                    if (relevantServices.isEmpty()) {
                        logger.error("[-] No service found for classpath: " + clazz + "." + name);
                    }

                    List<String> hitRoutes = getHitRoutes(route, relevantServices);
                    if (!hitRoutes.isEmpty()) {
                        isExposed = true;
                    }

                    Endpoint endpoint = new Endpoint(name, clazz, route, m, isExposed, hitRoutes, relevantServices);
                    endpoints.add(endpoint);
                }
            });
        });
        return endpoints;
    }

    private String routeHandler(String route, String clazz) {
        // model for yudao-cloud
        if (clazz.contains("yudao")) {
            return model4Yudao(route, clazz);
        }
        return route;
    }

    private String model4Yudao(String route, String clazz) {
        Map<String, String> patterns = Map.of(
                ".*\\.controller\\.app\\..*", "/app-api",
                ".*\\.controller\\.admin\\..*", "/admin-api"
        );

        Optional<Map.Entry<String, String>> match = patterns.entrySet().stream()
                .filter(e -> Pattern.matches(e.getKey(), clazz))
                .findFirst();

        return match.map(e -> e.getValue() + route).orElse(route);
    }

    private List<Service> matchService(String classpath) {
        // Filter services based on the classpath
        return GatewayParser.services.stream()
                .filter(service -> service.getClassList().stream().anyMatch(s -> s.contains(classpath.replace(".", "/").replace("com/", ""))))
                .collect(Collectors.toList());
    }

    private List<String> getHitRoutes(String route, List<Service> matchedServices) {
        if (Arrays.stream(Config.classpathKeywords).anyMatch(k ->
                k.contains("springblade")
                        || k.contains("gruul")
                        || k.contains("medusa")
                        || k.contains("cn.zealon")
                        || k.contains("pig4cloud.pig")
                        || k.contains("youlai"))) {
            return new ArrayList<>(List.of("EXPOSED"));
        }
        return matchedServices.stream()
                .flatMap(service -> Optional.ofNullable(service.getRoute())
                        .map(Collection::stream)
                        .orElseGet(Stream::empty)) // Flatten all routes into a single stream
                .filter(serviceRoute -> {
                    Pattern routePattern = Pattern.compile(processRule(serviceRoute));
                    return routePattern.matcher(route).find();
                })
                .collect(Collectors.toList());
    }

    private String processRule(String serviceRoute) {
        serviceRoute = serviceRoute.replace("**/", ".*");  // adapt MMall
        serviceRoute = serviceRoute.replace("**", ".*");
        return serviceRoute;
    }

    private String getRouteString(Collection<Annotation> annotations) {
        Optional<String> preRoute = annotations.stream()
                .filter(anno -> anno.getType().matches("org\\.springframework\\.web\\.bind\\.annotation.*"))
                .map(anno -> anno.getElement("value"))
                .filter(Objects::nonNull)
                .findFirst()
                .map(Object::toString)
                .map(value -> value.substring(1, value.length() - 1))
                .map(value -> value.split(","))
                .filter(values -> values.length > 0)
                .map(values -> values[0])
                .map(String::trim)
                .map(value -> value.substring(1, value.length() - 1));
        return preRoute.orElse("");
    }

}
