/**
 * @program: Tai-e-microservice
 * @author: LFY
 * @create: 2024-03-26 14:53
 **/

package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.entity.Endpoint;
import fdu.secsys.microservice.plugin.gateway.EndpointHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GatewaySourcePlugin implements Plugin {

    private static final Logger logger = LogManager.getLogger(GatewaySourcePlugin.class);

    private Solver solver;

    private static List<Endpoint> endpoints = new ArrayList<>();

    public static List<Endpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        endpoints = new EndpointHandler(solver.getHierarchy()).getEndpoints();
        endpoints.forEach(endpoint -> {
            if (endpoint.isExposed()) {
                logger.info("[+] Exposed endpoint: %s".formatted(endpoint.getRoute()));
            } else {
                logger.info("[-] Unexposed endpoint: %s".formatted(endpoint.getRoute() + ": " + endpoint.getClazz() + "." + endpoint.getName()));
            }
        });
    }
}
