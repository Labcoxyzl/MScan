package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.Arrays;
import java.util.function.Predicate;


public class GrpcPlugin implements Plugin {

    Logger logger = LogManager.getLogger(GrpcPlugin.class);

    Solver solver;

    MultiMap<Var, Invoke> var2InvokeMap = Maps.newMultiMap();

    MultiMap<Invoke, JMethod> invoke2calleeMap = Maps.newMultiMap();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        JClass stubClass = World.get().getClassHierarchy().getClass("io.grpc.stub.AbstractStub");
        JClass serviceClass = World.get().getClassHierarchy().getClass("io.grpc.BindableService");
        MultiMap<String, JMethod> serviceMethod = Maps.newMultiMap();
        World.get().getClassHierarchy().getAllSubclassesOf(serviceClass).stream().filter(Predicate.not(JClass::isAbstract)).filter(jClass -> Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))).forEach(jClass -> {
            jClass.getDeclaredMethods().stream().filter(jMethod -> jMethod.getParamCount() > 0).forEach(jMethod -> {
                serviceMethod.put(jMethod.getName(), jMethod);
            });
        });
        World.get().getClassHierarchy().applicationClasses().filter(jClass -> Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))).forEach(jClass -> {
            jClass.getDeclaredMethods().stream().filter(jMethod -> !jMethod.isAbstract()).forEach(jMethod -> jMethod.getIR().invokes(false).filter(invoke -> !invoke.isSpecial()).forEach(invoke -> {
                JMethod callee = invoke.getMethodRef().resolveNullable();
                if (callee != null && World.get().getClassHierarchy().isSubclass(stubClass, callee.getDeclaringClass()) && !callee.getDeclaringClass().getName().startsWith("io.grpc.") && !callee.getName().equals("build")) {
                    if (callee.getParamCount() > 0) {
                        Var var = InvokeUtils.getVar(invoke, 0);
                        if (var != null) {
                            var2InvokeMap.put(var, invoke);
                            if (serviceMethod.containsKey(callee.getName())) {
                                invoke2calleeMap.putAll(invoke, serviceMethod.get(callee.getName()));
                            }
                        } else {
                            logger.debug("var is none");
                        }
                    }
                }
            }));
        });
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        StmtVisitor<Void> visitor = new GrpcPlugin.Visitor(csMethod);
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
            CSManager csManager = solver.getCSManager();
            ContextSelector contextSelector = solver.getContextSelector();
            if (invoke2calleeMap.containsKey(callSite)) {
                invoke2calleeMap.get(callSite).forEach(callee -> {
                    solver.addPFGEdge(csManager.getCSVar(context, InvokeUtils.getVar(callSite, 0)), csManager.getCSVar(contextSelector.getEmptyContext(), callee.getIR().getParam(0)), FlowKind.PARAMETER_PASSING);
                    if(InvokeUtils.isCompatiable(callSite, callee)) {
                        solver.addCallEdge(new Edge<>(CallKind.VIRTUAL, csManager.getCSCallSite(context, callSite), csManager.getCSMethod(contextSelector.getEmptyContext(), callee)));
                    }
                });
            }
            if (!callSite.isDynamic()) {
                // address guice DI
                JMethod jMethod = callSite.getMethodRef().resolveNullable();
                if (jMethod != null) {
                    if (jMethod.getSignature().equals("<com.sitewhere.event.grpc.EventManagementImpl: com.sitewhere.microservice.api.event.IDeviceEventManagement getDeviceEventManagement()>")) {
                        Var result = InvokeUtils.getVar(callSite, InvokeUtils.RESULT);
                        CSVar csVar = csManager.getCSVar(context, result);
                        World.get().getClassHierarchy().getAllSubclassesOf(World.get().getClassHierarchy().getClass("com.sitewhere.microservice.api.event.IDeviceEventManagement")).stream().filter(jClass -> !jClass.isAbstract()).forEach(jClass -> {
                            Obj obj = solver.getHeapModel().getMockObj(Descriptor.ENTRY_DESC, callSite, jClass.getType());
                            solver.addPointsTo(csVar, obj);
                        });
                    } else if(jMethod.getSignature().equals("<com.sitewhere.web.rest.controllers.DeviceEvents: com.sitewhere.microservice.api.event.IDeviceEventManagement getDeviceEventManagement()>")) {
                        Var result = InvokeUtils.getVar(callSite, InvokeUtils.RESULT);
                        CSVar csVar = csManager.getCSVar(context, result);
                        World.get().getClassHierarchy().getAllSubclassesOf(World.get().getClassHierarchy().getClass("com.sitewhere.grpc.client.spi.client.IDeviceEventManagementApiChannel")).stream().filter(jClass -> !jClass.isAbstract()).forEach(jClass -> {
                            Obj obj = solver.getHeapModel().getMockObj(Descriptor.ENTRY_DESC, callSite, jClass.getType());
                            solver.addPointsTo(csVar, obj);
                        });
                    }
                }
            }
            return null;
        }
    }
}
