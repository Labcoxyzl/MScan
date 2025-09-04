package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.util.DubboUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.List;
import java.util.Optional;

public class DubboPlugin implements Plugin {

    Logger logger = LogManager.getLogger(DubboPlugin.class);

    Solver solver;

    List<JClass> dubboServices;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        dubboServices = World.get().getClassHierarchy().applicationClasses().filter(DubboUtil::isDubboService).toList();
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
                JClass declaringClass = methodRef.getDeclaringClass();
                if (declaringClass != null && declaringClass.isInterface()) {
                    Optional<JClass> serviceClassOp = dubboServices.stream().filter(jClass -> World.get().getClassHierarchy().isSubclass(declaringClass, jClass)).findFirst();
                    if(serviceClassOp.isPresent()) {
                        JClass serviceClass = serviceClassOp.get();
                        JMethod targetMethod = serviceClass.getDeclaredMethods().stream().filter(method -> method.getSubsignature().equals(methodRef.getSubsignature())).findFirst().get();
                        if (callSite.getInvokeExp().getArgCount() > targetMethod.getParamCount()) {
                            return null;
                        }
                        CSManager csManager = solver.getCSManager();
                        CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                        Context calleeCtx = solver.getContextSelector().getEmptyContext();
                        CSMethod csCallee = csManager.getCSMethod(calleeCtx, targetMethod);
                        solver.addCallEdge(new Edge<>(CallGraphs.getCallKind(callSite), csCallSite, csCallee));
                    }
                }
            }
            return null;
        }

    }
}
