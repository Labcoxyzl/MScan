package fdu.secsys.microservice.util;

import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.entity.Endpoint;
import fdu.secsys.microservice.plugin.GatewaySourcePlugin;
import pascal.taie.language.classes.JMethod;

import java.util.List;

public class GatewayUtil {
    public static boolean isExposed(JMethod jMethod) {
        List<Endpoint> endpoints = GatewaySourcePlugin.getEndpoints();
        if (endpoints.isEmpty()) {
            return true;
        }
        return endpoints.stream().anyMatch(endpoint -> endpoint.isExposed()
                && endpoint.getClazz().equals(jMethod.getDeclaringClass().getName())
                && endpoint.getMethod().getSignature().equals(jMethod.getSignature()));
    }
}
