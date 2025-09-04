package pascal.taie.analysis.pta.plugin.taint.container.spring;

import fdu.secsys.microservice.Config;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.annotation.*;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.*;

public class Utils {

    private static final String PRIMARY_ANNOTATION = "org.springframework.context.annotation.Primary";
    private static final String RESOURCE_ANNOTATION = "javax.annotation.Resource";
    private static final String JAKARTA_RESOURCE_ANNOTATION = "jakarta.annotation.Resource";
    private static final String AUTOWIRED_ANNOTATION = "org.springframework.beans.factory.annotation.Autowired";
    private static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";
    private static final String CONFIGURATION_ANNOTATION = "org.springframework.context.annotation.Configuration";
    private static final String REQUEST_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String GET_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.GetMapping";
    private static final String POST_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.PostMapping";
    private static final String PUT_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.PutMapping";
    private static final String DELETE_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.DeleteMapping";
    private static final String CONFIGURATION_PROPERTIES_ANNOTATION = "org.springframework.boot.context.properties.ConfigurationProperties";

    private static final List<String> controller = List.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            REQUEST_MAPPING_ANNOTATION,
            GET_MAPPING_ANNOTATION,
            POST_MAPPING_ANNOTATION,
            PUT_MAPPING_ANNOTATION,
            DELETE_MAPPING_ANNOTATION,
            "javax.ws.rs.Path"
    );

    private static final List<String> mapping = List.of(
            REQUEST_MAPPING_ANNOTATION,
            GET_MAPPING_ANNOTATION,
            POST_MAPPING_ANNOTATION,
            PUT_MAPPING_ANNOTATION,
            DELETE_MAPPING_ANNOTATION
    );

    private static final List<String> diAnno = List.of(
            AUTOWIRED_ANNOTATION,
            RESOURCE_ANNOTATION,
            JAKARTA_RESOURCE_ANNOTATION
    );

    private static final Set<String> condAnno = Set.of(
            "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty"
    );

    private static final List<String> beanAnno = List.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.apache.ibatis.annotations.Mapper",
            "org.springframework.cloud.openfeign.FeignClient",
            "com.alibaba.boot.hsf.annotation.HSFProvider",
            CONFIGURATION_PROPERTIES_ANNOTATION
    );

    private static final List<String> safeClass = List.of(
            "jakarta.servlet.http.HttpSession",
            "javax.servlet.http.HttpServletRequest",
            "javax.servlet.ServletRequest",
            "javax.servlet.http.HttpServletResponse",
            "javax.servlet.ServletResponse"
    );

    private static final List<String> javaRS = List.of(
            "javax.ws.rs.Path",
            "javax.ws.rs.GET",
            "javax.ws.rs.POST",
            "javax.ws.rs.PUT",
            "javax.ws.rs.DELETE"
    );

    public static boolean isController(Annotated anno) {
        if(anno instanceof JClass jClass && isControllerByParam((JClass)anno)) {
            return jClass.getName().contains("saleapi");
        }
        if(anno instanceof JMethod  jMethod && isControllerByParam((JMethod)anno)) {
            return jMethod.getDeclaringClass().getName().contains("saleapi");
        }
        if(anno.getAnnotations().stream().anyMatch(a -> controller.contains(a.getType()))) {
            return true;
        }
        if(anno instanceof JMethod jMethod) {
            List<JClass> interfaces = new ArrayList<>(jMethod.getDeclaringClass().getInterfaces());
            jMethod.getDeclaringClass().getInterfaces().forEach(inf -> interfaces.addAll(inf.getInterfaces()));
            JClass superClass = jMethod.getDeclaringClass().getSuperClass();
            while (superClass != null && Arrays.stream(Config.classpathKeywords).anyMatch(superClass.getName()::contains)) {
                interfaces.addAll(superClass.getInterfaces());
                superClass.getInterfaces().forEach(inf -> interfaces.addAll(inf.getInterfaces()));
                JMethod superMethod = superClass.getDeclaredMethod(jMethod.getSubsignature());
                if(superMethod != null) {
                    if(superMethod.getAnnotations().stream().anyMatch(a -> controller.contains(a.getType()))) {
                        return true;
                    }
                }
                superClass = superClass.getSuperClass();
            }
            Optional<JClass> optional = interfaces.stream().filter(inf -> Arrays.stream(Config.classpathKeywords).anyMatch(inf.getName()::contains)).filter(inf -> {
                JMethod interfaceMethod = inf.getDeclaredMethod(jMethod.getSubsignature());
                if(interfaceMethod != null) {
                    if(interfaceMethod.getAnnotations().stream().anyMatch(a -> controller.contains(a.getType()))) {
                        return true;
                    }
                }
                return false;
            }).findFirst();
            if(optional.isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isJavaxRS(Annotated anno) {
        return anno.getAnnotations().stream().anyMatch(a -> {
            return javaRS.contains(a.getType());
        });
    }

    public static boolean isControllerByParam(JClass jClass) {
        if(!jClass.isAbstract()) {
            if(jClass.getDeclaredMethods().stream().anyMatch(jMethod -> {
                int pc = jMethod.getParamCount();
                for(int i = 0; i < pc; i++) {
                    if(jMethod.getParamAnnotations(i).stream().anyMatch(anno -> anno.getType().equals("com.alibaba.citrus.turbine.dataresolver.Param"))) {
                        return true;
                    }
                }
                return false;
            })) {
                return true;
            };
        }
        return false;
    }

    public static boolean isControllerByParam(JMethod jMethod) {
        if(!jMethod.isAbstract()) {
            int pc = jMethod.getParamCount();
            for(int i = 0; i < pc; i++) {
                if(jMethod.getParamAnnotations(i).stream().anyMatch(anno -> anno.getType().equals("com.alibaba.citrus.turbine.dataresolver.Param"))) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public static boolean isBean(Annotated anno) {
        return anno.getAnnotations().stream().anyMatch(a -> beanAnno.contains(a.getType()) || condAnno.contains(a.getType()));
    }

    public static boolean isConfiguration(Annotated anno) {
        return anno.getAnnotations().stream().anyMatch(a -> CONFIGURATION_ANNOTATION.equals(a.getType()));
    }

    public static boolean isBeanByConfiguration(Annotated anno) {
        return anno.getAnnotations().stream().anyMatch(a -> BEAN_ANNOTATION.equals(a.getType()));
    }

    public static boolean isDI(Annotated anno) {
        return anno.getAnnotations().stream().anyMatch(a -> diAnno.contains(a.getType()));
    }

    public static boolean isAutowired(Annotation anno) {
        return anno.getType().equals(AUTOWIRED_ANNOTATION);
    }

    public static boolean isQualifier(Annotation anno) {
        return anno.getType().equals(QUALIFIER_ANNOTATION);
    }

    public static boolean isControlParam(Var param) {
        return !safeClass.contains(param.getType().getName());
    }

    public static SpringBean getSpringBeanByAnno(JClass jClass, Obj obj) {
        String beanName = jClass.getAnnotations().stream().filter(a -> beanAnno.contains(a.getType()) || condAnno.contains(a.getType())).map(a -> {
            if (!a.getType().equals(CONFIGURATION_PROPERTIES_ANNOTATION) && !condAnno.contains(a.getType())) {
                if (a.hasElement("value")) {
                    return ((StringElement) a.getElement("value")).value();
                }
            }
            String simpleName = jClass.getSimpleName();
            String name = String.valueOf(simpleName.charAt(0)).toLowerCase();
            if (simpleName.length() > 1) {
                name += simpleName.substring(1);
            }
            return name;
        }).findFirst().get();
        boolean isPrimary = jClass.hasAnnotation(PRIMARY_ANNOTATION);
        return new SpringBean(Set.of(beanName), isPrimary, obj);
    }

    public static SpringBean getSpringBeanByMethod(JMethod jMethod, Obj obj, List<Var> retVars) {
        Annotation beanAnno = jMethod.getAnnotation(BEAN_ANNOTATION);
        if (beanAnno == null) return null;
        Set<String> names = new HashSet<>();
        if (beanAnno.hasElement("name")) {
            for (Element element : ((ArrayElement) beanAnno.getElement("name")).elements()) {
                names.add(((StringElement) element).value());
            }
        } else if (beanAnno.hasElement("value")) {
            for (Element element : ((ArrayElement) beanAnno.getElement("value")).elements()) {
                names.add(((StringElement) element).value());
            }
        } else {
            names.add(jMethod.getName());
        }
        boolean isPrimate = jMethod.hasAnnotation(PRIMARY_ANNOTATION);
        return new SpringBean(names, isPrimate, obj, retVars);
    }

    public static InjectFieldInfo getInjectFiledInfo(JField jField) {
        Annotation autowired = jField.getAnnotation(AUTOWIRED_ANNOTATION);
        Annotation resource = jField.getAnnotation(RESOURCE_ANNOTATION);
        if (resource == null) {
            resource = jField.getAnnotation(JAKARTA_RESOURCE_ANNOTATION);
        }
        if (autowired != null) {
            boolean request = true;
            if (autowired.hasElement("required")) {
                Element element = autowired.getElement("required");
                if (element instanceof IntElement intElement) {
                    request = intElement.value() != 0;
                } else {
                    request = ((BooleanElement) element).value();
                }
            }
            return new InjectFieldInfo(true, request, null);
        } else if (resource != null) {
            String name = jField.getName();
            Annotation qualifier = jField.getAnnotation(QUALIFIER_ANNOTATION);
            if (qualifier != null && qualifier.hasElement("value")) {
                name = ((StringElement) qualifier.getElement("value")).value();
                if (name == null) {
                    return new InjectFieldInfo(true, false, name);
                }
            } else if (resource != null && resource.hasElement("name")) {
                name = ((StringElement) resource.getElement("name")).value();
                if (name == null) {
                    return new InjectFieldInfo(true, false, name);
                }
            }
            return new InjectFieldInfo(true, false, name);
        } else {
            List<String> targetField = List.of("skipperClient", "skipperStreamDeployer", "streamService");
            if(targetField.contains(jField.getName())) {
                return new InjectFieldInfo(false, false, jField.getName());
            }
        }
        return null;
    }

    public static List<InjectParamInfo> getInjectParamsInfo(JMethod jMethod) {
        List<InjectParamInfo> injectParamInfos = new ArrayList<>();
        for (Var param : jMethod.getIR().getParams()) {
            Collection<Annotation> paramAnnotations = jMethod.getParamAnnotations(jMethod.isStatic() ? param.getIndex() : param.getIndex() - 1);
            Optional<Annotation> autowiredAnnoOptional = paramAnnotations.stream().filter(Utils::isAutowired).findFirst();
            Optional<Annotation> qualifierAnnoOptional = paramAnnotations.stream().filter(Utils::isQualifier).findFirst();
            boolean byType;
            boolean request;
            String qualifier = null;
            if (qualifierAnnoOptional.isPresent()) {
                Annotation qualifierAnno = qualifierAnnoOptional.get();
                byType = false;
                request = false;
                qualifier = qualifierAnno.hasElement("value") ?
                        ((StringElement) qualifierAnno.getElement("value")).value() : "";
            } else {
                byType = true;
                if (autowiredAnnoOptional.isPresent()) {
                    Annotation autowiredAnno = autowiredAnnoOptional.get();
                    request = true;
                    if (autowiredAnno.hasElement("required")) {
                        Element element = autowiredAnno.getElement("required");
                        if (element instanceof IntElement intElement) {
                            request = intElement.value() != 0;
                        } else {
                            request = ((BooleanElement) element).value();
                        }
                    }
                } else {
                    request = false;
                }
            }
            String type = param.getType().getName();
            injectParamInfos.add(new InjectParamInfo(byType, request, qualifier, type, jMethod.isStatic() ? param.getIndex() : param.getIndex() - 1));
        }
        return injectParamInfos;
    }

    public static List<InjectParamInfo> getDefaultInjectParamsInfo(JMethod jMethod) {
        List<InjectParamInfo> injectParamInfos = new ArrayList<>();
        for (Var param : jMethod.getIR().getParams()) {
            String type = param.getType().getName();
            injectParamInfos.add(new InjectParamInfo(true, true, null, type, jMethod.isStatic() ? param.getIndex() : param.getIndex() - 1));
        }
        return injectParamInfos;
    }

}
