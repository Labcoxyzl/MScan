package fdu.secsys.microservice.plugin;

import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.EnhanceHandlerContext;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.List;

public class MiscPlugin implements Plugin {

    Solver solver;

    public MiscPlugin(EnhanceHandlerContext context) {
        solver = context.solver();
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
        public Void visit(Invoke stmt) {
            List<String> sigs = new ArrayList<>(List.of(
                    "<cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient: java.lang.String upload(byte[],java.lang.String,java.lang.String)>",
                    "<top.tangyh.lamp.file.strategy.FileStrategy: top.tangyh.lamp.file.entity.File upload(org.springframework.web.multipart.MultipartFile,java.lang.String,java.lang.String)>"
            ));
            if (stmt.isDynamic()) {
                return null;
            }
            MethodRef methodRef = stmt.getMethodRef();
            JMethod jMethod = methodRef.resolveNullable();
            if (jMethod != null && sigs.stream().anyMatch(sig -> sig.equals(jMethod.getSignature()))) {
                Var var = InvokeUtils.getVar(stmt, InvokeUtils.BASE);
                JClass jClass = jMethod.getDeclaringClass();
                HeapModel heapModel = solver.getHeapModel();
                CSManager csManager = solver.getCSManager();
                ContextSelector contextSelector = solver.getContextSelector();
                PointsToSet pointsToSet = solver.makePointsToSet();
                World.get().getClassHierarchy().getAllSubclassesOf(jClass).stream()
                        // don't exclude itself because sometimes DI is a concrete type
                        .filter(c -> !c.isAbstract()).forEach(c -> {
                            Obj obj = heapModel.getMockObj(() -> "model:%s".formatted(jMethod.getName()), "MiscPlugin", c.getType());
                            CSObj csObj = csManager.getCSObj(contextSelector.selectHeapContext(csMethod, obj), obj);
                            pointsToSet.addObject(csObj);
                        });
                solver.addVarPointsTo(context, var, pointsToSet);
            }
            return null;
        }
    }
}
