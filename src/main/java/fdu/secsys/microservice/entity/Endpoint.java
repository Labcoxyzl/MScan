/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 15:42
 **/

package fdu.secsys.microservice.entity;

import lombok.Data;
import pascal.taie.language.classes.JMethod;

import java.util.List;

@Data
public class Endpoint {

    // endpoint method name
    private String name;

    // endpoint class path
    private String clazz;

    // endpoint route path
    private String route;

    // endpoint jMethod
    private JMethod method;

    // exposed by gateway or not
    private boolean isExposed;

    // hit routing rule
    private List<String> hitRoute;

    // relevant microservice name
    private List<Service> relevantService;

    public Endpoint(String name, String clazz, String route, JMethod method, boolean isExposed, List<String> hitRoute, List<Service> relevantService) {
        this.name = name;
        this.clazz = clazz;
        this.route = route;
        this.method = method;
        this.isExposed = isExposed;
        this.hitRoute = hitRoute;
        this.relevantService = relevantService;
    }

}
