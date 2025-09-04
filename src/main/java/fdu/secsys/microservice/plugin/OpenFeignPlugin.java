package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.entity.Endpoint;
import fdu.secsys.microservice.util.FeignUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.container.spring.Utils;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.List;
import java.util.Set;

public class OpenFeignPlugin implements Plugin {

    Logger logger = LogManager.getLogger(OpenFeignPlugin.class);

    Solver solver;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    MultiMap<String, JMethod> feignMappings = Maps.newMultiMap();

    MultiMap<String, JMethod> handlerMappings = Maps.newMultiMap();

    MultiMap<String, JMethod> mappingEdges = Maps.newMultiMap();

    @Override
    public void onStart() {
        List<Endpoint> endpoints = GatewaySourcePlugin.getEndpoints();

        endpoints.forEach(endpoint -> {
            JMethod jMethod = endpoint.getMethod();
            if (FeignUtil.isFeignClient(jMethod.getDeclaringClass())) {
                feignMappings.put(endpoint.getRoute(), jMethod);
            } else if (Utils.isController(jMethod.getDeclaringClass())) {
                handlerMappings.put(endpoint.getRoute(), jMethod);
            }
        });

        feignMappings.forEach((route, feignClient) -> {
            Set<JMethod> handlers = handlerMappings.get(route);
            mappingEdges.putAll(feignClient.getSignature(), handlers);
        });
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        StmtVisitor<Void> visitor = new Visitor(csMethod);
        csMethod.getMethod().getIR().getStmts().forEach(stmt -> stmt.accept(visitor));
    }

    private class Visitor implements StmtVisitor<Void> {
        private final CSMethod csMethod;

        private final Context context;

        private Visitor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        @Override
        public Void visit(Invoke callSite) {
            if (callSite.isInterface()) {
                MethodRef methodRef = callSite.getMethodRef();
                if (FeignUtil.isFeignClient(methodRef.getDeclaringClass())) {
                    String sig = methodRef.toString();
                    Set<JMethod> jMethods;
                    jMethods = mappingEdges.get(sig);
                    CSManager csManager = solver.getCSManager();
                    ContextSelector contextSelector = solver.getContextSelector();
                    CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                    jMethods.forEach(callee -> {
                        if (callSite.getInvokeExp().getArgCount() > callee.getParamCount()) {
                            logger.warn("[!] not match param num {} {}", callSite.getInvokeExp().getMethodRef(), callee);
                            return;
                        }
                        Context calleeCtx = contextSelector.getEmptyContext();
                        CSMethod csCallee = csManager.getCSMethod(calleeCtx, callee);
                        solver.addCallEdge(new Edge<>(CallGraphs.getCallKind(callSite), csCallSite, csCallee));
                    });
                }
            }
            return null;
        }
    }

}
