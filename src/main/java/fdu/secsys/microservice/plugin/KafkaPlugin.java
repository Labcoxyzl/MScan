package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.util.KafkaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.ConstantObj;
import pascal.taie.analysis.pta.core.heap.MergedObj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.SignatureMatcher;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class KafkaPlugin implements Plugin {

    Logger logger = LogManager.getLogger(KafkaPlugin.class);

    Solver solver;

    MultiMap<String, JMethod> kafkaListeners;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        kafkaListeners = Maps.newMultiMap();
        World.get().getClassHierarchy().applicationClasses().forEach(jClass -> jClass.getDeclaredMethods().stream().filter(KafkaUtil::isKafkaListener).forEach(jMethod -> {
            String topic = KafkaUtil.getListenerTopic(jMethod);
            if (!Objects.equals(topic, "")) {
                kafkaListeners.put(topic, jMethod);
            }
        }));
        SignatureMatcher signatureMatcher = new SignatureMatcher(solver.getHierarchy());
        kafkaSendSignatures.forEach(kafkaSendSignature -> {
            signatureMatcher.getMethods(kafkaSendSignature).forEach(jMethod -> {
                kafkaSendMethods.add(jMethod);
            });
        });
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        if (stmt instanceof Invoke invoke && !invoke.isDynamic()) {
            JMethod target = invoke.getMethodRef().resolveNullable();
            if (target != null) {
                if (kafkaSendMethods.stream().anyMatch(jMethod -> jMethod.getSubsignature().equals(target.getSubsignature()))) {
                    Var var = InvokeUtils.getVar(invoke, 0);
                    if (var != null) {
                        var2LocalInvoke.put(var, invoke);
                    }
                }
            }
        }
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Var var = csVar.getVar();
        if (var2LocalInvoke.containsKey(var)) {
            pts.forEach(csObj -> {
                Set<String> stringValues = new HashSet<>();
                if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                    stringValues.add(stringLiteral.getString());
                } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                    mergedObj.getAllocation().forEach(representativeObj -> {
                        if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                            stringValues.add(stringLiteral.getString());
                        }
                    });
                }
                stringValues.forEach(stringValue -> {
                    if (kafkaListeners.containsKey(stringValue)) {
                        CSManager csManager = solver.getCSManager();
                        kafkaListeners.get(stringValue).forEach(callee -> {
                            Context calleeCtx = solver.getContextSelector().getEmptyContext();
                            CSMethod csCallee = csManager.getCSMethod(calleeCtx, callee);
                            CSVar sinkPointer = csManager.getCSVar(solver.getContextSelector().getEmptyContext(), callee.getIR().getParam(0));
                            var2LocalInvoke.get(var).forEach(callSite -> {
                                CSCallSite csCallSite = csManager.getCSCallSite(csVar.getContext(), callSite);
                                CSVar sourcePointer = csManager.getCSVar(csVar.getContext(), InvokeUtils.getVar(callSite, 1));
                                solver.addPFGEdge(sourcePointer, sinkPointer, FlowKind.PARAMETER_PASSING);
                                if(InvokeUtils.isCompatiable(callSite, callee)) {
                                    solver.addCallEdge(new Edge<>(CallGraphs.getCallKind(callSite), csCallSite, csCallee));
                                }
                            });
                        });
                    }
                });
            });
        }
    }

    static List<String> kafkaSendSignatures = List.of("<org.springframework.kafka.core.KafkaTemplate: org.springframework.util.concurrent.ListenableFuture send(java.lang.String,java.lang.Object)>");

    Set<JMethod> kafkaSendMethods = new HashSet<>();

    MultiMap<Var, Invoke> var2LocalInvoke = Maps.newMultiMap();
}
