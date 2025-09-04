package fdu.secsys.microservice.util;

import fdu.secsys.microservice.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.language.annotation.Annotated;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.ArrayElement;
import pascal.taie.language.annotation.StringElement;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.*;

public class FeignUtil {

    static Logger logger = LogManager.getLogger(FeignUtil.class);

    static Set<String> feignClientAnno = new HashSet<>(Set.of(".springframework.cloud.netflix.feign.FeignClient", ".springframework.cloud.openfeign.FeignClient"));

    static Set<String> springControllerAnno = new HashSet<>(Set.of(
            ".springframework.web.bind.annotation.Controller",
            ".springframework.web.bind.annotation.RestController"
    ));

    static Set<String> mappingAnno = new HashSet<>(Set.of(
            ".springframework.web.bind.annotation.GetMapping",
            ".springframework.web.bind.annotation.PostMapping",
            ".springframework.web.bind.annotation.RequestMapping",
            ".springframework.web.bind.annotation.PutMapping",
            ".springframework.web.bind.annotation.DeleteMapping"
    ));

    static Set<String> rsAnno = new HashSet<>(Set.of(
            ".ws.rs.Path",
            ".ws.rs.Get",
            ".ws.rs.Post",
            ".ws.rs.Put",
            ".ws.rs.Delete"
    ));

    public static boolean isFeignClient(JClass jClass) {
        return jClass.isInterface() && matchAnnotationSuffix(jClass.getAnnotations(), new ArrayList(List.of(".FeignClient")));
    }

    public static boolean matchAnnotationSuffix(Collection<Annotation> toMatches, Collection matchers) {
        return toMatches.stream().anyMatch(toMatch -> matchers.stream().anyMatch(matcher -> toMatch.getType().endsWith(matcher.toString())));
    }

    public static boolean isMapping(Annotation annotation) {
        if(rsAnno.stream().anyMatch(suffix -> annotation.getType().endsWith(suffix))) {
            return true;
        }
        return mappingAnno.stream().anyMatch(suffix -> annotation.getType().endsWith(suffix));
    }

    public static List<String> getMappings(Annotated annotated) {
        List<String> mappings = new ArrayList<>();
        Optional<Annotation> optional = annotated.getAnnotations().stream().filter(FeignUtil::isMapping).findFirst();
        if (optional.isEmpty()) {
            if(annotated instanceof JMethod jMethod) {
                List<JClass> interfaces = new ArrayList<>(jMethod.getDeclaringClass().getInterfaces());
                jMethod.getDeclaringClass().getInterfaces().forEach(inf -> interfaces.addAll(inf.getInterfaces()));
                JClass superClass = jMethod.getDeclaringClass().getSuperClass();
                while (superClass != null && Arrays.stream(Config.classpathKeywords).anyMatch(superClass.getName()::contains)) {
                    interfaces.addAll(superClass.getInterfaces());
                    superClass.getInterfaces().forEach(inf -> interfaces.addAll(inf.getInterfaces()));
                    JMethod superMethod = superClass.getDeclaredMethod(jMethod.getSubsignature());
                    if(superMethod != null) {
                        optional = superMethod.getAnnotations().stream().filter(FeignUtil::isMapping).findFirst();
                    }
                    superClass = superClass.getSuperClass();
                }
                if(optional.isEmpty()) {
                    List<Annotation> candidates = new ArrayList<>();
                    interfaces.stream().filter(inf -> Arrays.stream(Config.classpathKeywords).anyMatch(inf.getName()::contains)).forEach(inf -> {
                        JMethod interfaceMethod = inf.getDeclaredMethod(jMethod.getSubsignature());
                        if(interfaceMethod != null) {
                            interfaceMethod.getAnnotations().stream().filter(FeignUtil::isMapping).forEach(candidates::add);
                        }
                    });
                    optional = candidates.stream().findFirst();
                }
            }
        }
        if(optional.isEmpty()) {
            return mappings;
        }
        try {
            if(optional.get().getElement("value") instanceof ArrayElement value) {
                if (value != null) {
                    value.elements().stream().map(element -> {
                        String normalized = element.toString();
                        if (normalized.startsWith("\"")) {
                            normalized = normalized.substring(1);
                        }
                        if (normalized.endsWith("\"")) {
                            normalized = normalized.substring(0, normalized.length() - 1);
                        }
                        return normalized;
                    }).forEach(mappings::add);
                }
            } else if(optional.get().getElement("path") instanceof ArrayElement value) {
                if (value != null) {
                    value.elements().stream().map(element -> {
                        String normalized = element.toString();
                        if (normalized.startsWith("\"")) {
                            normalized = normalized.substring(1);
                        }
                        if (normalized.endsWith("\"")) {
                            normalized = normalized.substring(0, normalized.length() - 1);
                        }
                        return normalized;
                    }).forEach(mappings::add);
                }
            } else if(optional.get().getElement("value") instanceof StringElement value) {
                String normalized = value.toString();
                if (normalized.startsWith("\"")) {
                    normalized = normalized.substring(1);
                }
                if (normalized.endsWith("\"")) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }
                mappings.add(normalized);
            }
        } catch (Exception e) {
            logger.error("{}: {}", optional.get(), e);
        }
        return mappings;
    }

    public static Optional<String> getFirstMapping(Annotated annotated) {
        List<String> mappings = getMappings(annotated);
        if (mappings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mappings.get(0));
    }

    public static String joinMapping(String prefix, String mapping) {
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (mapping.startsWith("/")) {
            mapping = mapping.substring(1);
        }
        return String.join("/", prefix, mapping);
    }

}
