package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.util.RabbitMQUtil;
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
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.SignatureMatcher;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.*;

public class RabbitMQPlugin implements Plugin {

    Logger logger = LogManager.getLogger(RabbitMQPlugin.class);

    Solver solver;

    MultiMap<String, JMethod> rabbitmqListeners;


    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        rabbitmqListeners = Maps.newMultiMap();
        World.get().getClassHierarchy().applicationClasses().forEach(jClass -> jClass.getDeclaredMethods().stream().filter(RabbitMQUtil::isRabbitMQListener).forEach(jMethod -> {
            String queue = RabbitMQUtil.getListenerQueue(jMethod);
            if (!Objects.equals(queue, "")) {
                rabbitmqListeners.put(queue, jMethod);
            }
        }));
        SignatureMatcher signatureMatcher = new SignatureMatcher(solver.getHierarchy());
        rabbitmqSendSignatures.forEach(rabbitmqSendSignature -> {
            signatureMatcher.getMethods(rabbitmqSendSignature).forEach(jMethod -> {
                rabbitmqSendMethods.add(jMethod);
            });
        });
        JClass jClass = World.get().getClassHierarchy().getClass("org.springframework.amqp.core.Binding");
        if (jClass != null) {
            jClass.getDeclaredMethods().forEach(jMethod -> {
                if (rabbitmqBindSignatures.contains(jMethod.getSignature())) {
                    rabbitmqBindMethods.add(jMethod);
                }
            });
        }
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        if (stmt instanceof Invoke invoke && !invoke.isDynamic()) {
            JMethod target = invoke.getMethodRef().resolveNullable();
            if (target != null) {
                if (rabbitmqSendMethods.stream().anyMatch(jMethod -> jMethod.getSubsignature().equals(target.getSubsignature()))) {
                    Var var = InvokeUtils.getVar(invoke, 0);
                    if (var != null) {
                        sendVar2LocalInvoke.put(var, invoke);
                    }
                    var = InvokeUtils.getVar(invoke, 1);
                    if (var != null) {
                        sendVar2LocalInvoke.put(var, invoke);
                    }
                }
                if (rabbitmqBindMethods.stream().anyMatch(jMethod -> jMethod.getSubsignature().equals(target.getSubsignature()))) {
                    Var var = InvokeUtils.getVar(invoke, 0);
                    if (var != null) {
                        // queue
                        bindVar2LocalInvoke.put(var, invoke);
                    }
                    var = InvokeUtils.getVar(invoke, 2);
                    if (var != null) {
                        // exchange
                        bindVar2LocalInvoke.put(var, invoke);
                    }
                    var = InvokeUtils.getVar(invoke, 3);
                    if (var != null) {
                        // route key
                        bindVar2LocalInvoke.put(var, invoke);
                    }
                }
            }
        }
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Var var = csVar.getVar();
        if (sendVar2LocalInvoke.containsKey(var)) {

            sendVar2LocalInvoke.get(var).forEach(callSite -> {
                Var exchangeVar = InvokeUtils.getVar(callSite, 0);
                Var routeKeyVar = InvokeUtils.getVar(callSite, 1);
                CSManager csManager = solver.getCSManager();
                Context context = csVar.getContext();
                Set<String> exchangeVarStringValues = new HashSet<>();
                Set<String> routeKeyVarStringValues = new HashSet<>();
                solver.getPointsToSetOf(csManager.getCSVar(context, exchangeVar)).forEach(csObj -> {
                    if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                        exchangeVarStringValues.add(stringLiteral.getString());
                    } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                        mergedObj.getAllocation().forEach(representativeObj -> {
                            if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                exchangeVarStringValues.add(stringLiteral.getString());
                            }
                        });
                    }
                });
                solver.getPointsToSetOf(csManager.getCSVar(context, routeKeyVar)).forEach(csObj -> {
                    if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                        routeKeyVarStringValues.add(stringLiteral.getString());
                    } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                        mergedObj.getAllocation().forEach(representativeObj -> {
                            if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                routeKeyVarStringValues.add(stringLiteral.getString());
                            }
                        });
                    }
                });
                exchangeVarStringValues.forEach(s1 -> {
                    routeKeyVarStringValues.forEach(s2 -> {
                        String key = s1 + "@" + s2;
                        if (bindMap.containsKey(key)) {
                            String value = bindMap.get(key);
                            if (rabbitmqListeners.containsKey(bindMap.get(key))) {
                                rabbitmqListeners.get(value).forEach(callee -> {
                                    Context calleeCtx = solver.getContextSelector().getEmptyContext();
                                    CSMethod csCallee = csManager.getCSMethod(calleeCtx, callee);
                                    CSVar sinkPointer = csManager.getCSVar(solver.getContextSelector().getEmptyContext(), callee.getIR().getParam(0));
                                    CSCallSite csCallSite = csManager.getCSCallSite(csVar.getContext(), callSite);
                                    CSVar sourcePointer = csManager.getCSVar(csVar.getContext(), InvokeUtils.getVar(callSite, 2));
                                    solver.addPFGEdge(sourcePointer, sinkPointer, FlowKind.PARAMETER_PASSING);
                                    if(InvokeUtils.isCompatiable(callSite, callee)) {
                                        solver.addCallEdge(new Edge<>(CallGraphs.getCallKind(callSite), csCallSite, csCallee));
                                    }
                                });
                            }
                        }
                    });
                });
            });
        }
        if (bindVar2LocalInvoke.containsKey(var)) {
            bindVar2LocalInvoke.get(var).forEach(callSite -> {
                Var queueVar = InvokeUtils.getVar(callSite, 0);
                Var exchangeVar = InvokeUtils.getVar(callSite, 2);
                Var routeKeyVar = InvokeUtils.getVar(callSite, 3);
                CSManager csManager = solver.getCSManager();
                Context context = csVar.getContext();
                Set<String> queueVarStringValues = new HashSet<>();
                Set<String> exchangeVarStringValues = new HashSet<>();
                Set<String> routeKeyVarStringValues = new HashSet<>();
                solver.getPointsToSetOf(csManager.getCSVar(context, queueVar)).forEach(csObj -> {
                    if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                        queueVarStringValues.add(stringLiteral.getString());
                    } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                        mergedObj.getAllocation().forEach(representativeObj -> {
                            if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                queueVarStringValues.add(stringLiteral.getString());
                            }
                        });
                    }
                });
                solver.getPointsToSetOf(csManager.getCSVar(context, exchangeVar)).forEach(csObj -> {
                    if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                        exchangeVarStringValues.add(stringLiteral.getString());
                    } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                        mergedObj.getAllocation().forEach(representativeObj -> {
                            if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                exchangeVarStringValues.add(stringLiteral.getString());
                            }
                        });
                    }
                });
                solver.getPointsToSetOf(csManager.getCSVar(context, routeKeyVar)).forEach(csObj -> {
                    if (csObj.getObject() instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                        routeKeyVarStringValues.add(stringLiteral.getString());
                    } else if (csObj.getObject() instanceof MergedObj mergedObj) {
                        mergedObj.getAllocation().forEach(representativeObj -> {
                            if (representativeObj instanceof ConstantObj constantObj && constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                routeKeyVarStringValues.add(stringLiteral.getString());
                            }
                        });
                    }
                });
                exchangeVarStringValues.forEach(s1 -> {
                    routeKeyVarStringValues.forEach(s2 -> {
                        queueVarStringValues.forEach(s3 -> {
                            bindMap.put(s1 + "@" + s2, s3);
                        });
                    });
                });
            });
        }
    }

    static List<String> rabbitmqSendSignatures = List.of("<org.springframework.amqp.rabbit.core.RabbitTemplate: void convertAndSend(java.lang.String,java.lang.String,java.lang.Object,org.springframework.amqp.rabbit.support.CorrelationData)>");

    Set<JMethod> rabbitmqSendMethods = new HashSet<>();

    static List<String> rabbitmqBindSignatures = List.of("<org.springframework.amqp.core.Binding: void <init>(java.lang.String,org.springframework.amqp.core.Binding$DestinationType,java.lang.String,java.lang.String,java.util.Map)>");

    Set<JMethod> rabbitmqBindMethods = new HashSet<>();

    MultiMap<Var, Invoke> sendVar2LocalInvoke = Maps.newMultiMap();

    MultiMap<Var, Invoke> bindVar2LocalInvoke = Maps.newMultiMap();

    Map<String, String> bindMap = new HashMap<>();
}
