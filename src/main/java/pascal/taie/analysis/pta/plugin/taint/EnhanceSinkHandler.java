/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.taint;

import fdu.secsys.microservice.util.ChainUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.ArrayIndex;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.MultiMapCollector;
import pascal.taie.util.collection.Sets;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles sinks in taint analysis.
 */
public class EnhanceSinkHandler implements Plugin {

    private static Logger logger = LogManager.getLogger(EnhanceSinkHandler.class);

    private final List<EnhanceSink> sinks;

    protected final Solver solver;

    protected final TaintManager manager;

    protected final boolean callSiteMode;

    protected EnhanceSinkHandler(EnhanceHandlerContext context) {
        sinks = context.config().sinks();
        solver = context.solver();
        manager = context.manager();
        callSiteMode = context.config().callSiteMode();
    }

    Set<TaintFlow> collectTaintFlows() {
        PointerAnalysisResult result = solver.getResult();
        Set<TaintFlow> taintFlows = Sets.newOrderedSet();
        MultiMap<String, EnhanceSink> sinkMap = sinks.stream()
                .collect(MultiMapCollector.get(s -> s.methodSig(), s -> s));
        // scan all reachable call sites to search sink calls
        AtomicInteger counter = new AtomicInteger();
        result.getCallGraph()
                .reachableMethods()
                .filter(m -> !m.isAbstract())
                .forEach(m -> {
                    m.getIR().invokes(false).forEach(callSite -> {
                        MethodRef methodRef = callSite.getMethodRef();
                        for (EnhanceSink enhanceSink : sinkMap.get(methodRef.toString())) {
                            if (Arrays.stream(World.get().getOptions().getClasspathKeywords()).anyMatch(k -> m.getDeclaringClass().getName().contains(k))) {
                                counter.getAndIncrement();
                                System.out.println("[*] sink %s in %s".formatted(callSite, m));
                            } else {
                                return;
                            }
                            int i = enhanceSink.index();
                            Var arg = InvokeUtils.getVar(callSite, i);
                            SinkPoint sinkPoint = new SinkPoint(callSite, i, null, null, null, null, enhanceSink.vulId());
                            checkTaint(result, taintFlows, arg, sinkPoint);
                        }
                    });
                    if (sinkMap.containsKey(m.getSignature())) {
                        Context entryCtx = solver.getContextSelector().getEmptyContext();
                        CSMethod csEntryMethod = solver.getCSManager().getCSMethod(entryCtx, m);
                        if (solver.getCallGraph().contains(csEntryMethod)) {
                            sinkMap.get(m.getSignature()).stream()
                                    .filter(enhanceSink -> enhanceSink.index() == -2)
                                    .forEach(enhanceSink -> {
                                        for (Var returnVar : m.getIR().getReturnVars()) {
                                            SinkPoint sinkPoint = new SinkPoint(null, enhanceSink.index(), m, returnVar, enhanceSink.excludeSourceParamAnno(), enhanceSink.excludeCallSource(), enhanceSink.vulId());
                                            checkTaint(result, taintFlows, returnVar, sinkPoint);
                                        }
                                    });
                        }
                    }
                });
        System.out.println("[*] sink callsite count: %s".formatted(counter.get()));
        Map<TaintFlow, Integer> lengths = new HashMap<>();
        Set<TaintFlow> filteredTaintFlows = taintFlows.stream().filter(taintFlow -> {
            try {
                JMethod sourceMethod = taintFlow.sourcePoint().getContainer();
                JMethod sinkMethod = taintFlow.sinkPoint().sinkCall() !=null ? taintFlow.sinkPoint().sinkCall().getContainer(): taintFlow.sinkPoint().var().getMethod();
                List<JMethod> chain = ChainUtil.breadthFirst(result.getCallGraph(), sourceMethod, sinkMethod);
                lengths.put(taintFlow, chain.size());
                Set<String> sigs = Set.of(
                        "<com.taobao.meta.aigc.service.AbstractQueryValidator: void checkQueryClause(java.util.List)>",
                        "<com.taobao.meta.aigc.service.AbstractQueryValidator: void checkOrderByClause(com.taobao.meta.aigc.api.dto.OrderByClause)>");
                if(chain.isEmpty()) return false;
                return chain.stream().noneMatch(jMethod -> !jMethod.isAbstract() && jMethod.getIR().invokes(false).anyMatch(invoke ->  {
                    JMethod sigMethod = invoke.getMethodRef().resolveNullable();
                    return sigs.contains(invoke.getMethodRef().toString()) || sigs.contains(sigMethod !=null ? sigMethod.toString() : null);
                }));
            } catch (Exception e) {
                logger.warn(e);
            }
            return true;
        }).collect(Collectors.toSet());
        Set<String> set = new HashSet<>();
        filteredTaintFlows = filteredTaintFlows.stream().filter(taintFlow -> {
            try {
                String sig = "%s-%s".formatted(taintFlow.sourcePoint().getContainer(), taintFlow.sinkPoint().sinkCall());
                if(!set.add(sig)) return false;
            } catch (Exception e) {
                logger.warn(e);
            }
            return true;
        }).collect(Collectors.toSet());
        try(FileWriter fileWriter = new FileWriter(new File(World.get().getOptions().getOutputDir(), "microservice-taint-flows.txt"))) {
            filteredTaintFlows.forEach(taintFlow -> {
                try {
                    fileWriter.write("%s %s\n".formatted(lengths.get(taintFlow), taintFlow));
                } catch (Exception e) {
                    logger.warn(e);
                }
            });
        } catch (Exception e) {
            logger.error(e);
        }
        return filteredTaintFlows;
    }

    private void checkTaint(PointerAnalysisResult result, Set<TaintFlow> taintFlows, Var arg, SinkPoint sinkPoint) {
        result.getPointsToSet(arg)
                .stream()
                .flatMap(obj -> {
                    if (manager.isTaint(obj)) {
                        return Stream.of(obj);
                    }
                    if (obj.getType() instanceof ArrayType) {
                        CSManager csManager = solver.getCSManager();
                        return csManager.getCSObjsOf(obj).stream()
                                .flatMap(csObj -> {
                                    ArrayIndex arrayIndex = csManager.getArrayIndex(csObj);
                                    if (arrayIndex != null && arrayIndex.getPointsToSet() != null) {
                                        return arrayIndex.getPointsToSet().objects()
                                                .filter(csObjForArrayIndex -> manager.isTaint(csObjForArrayIndex.getObject()))
                                                .map(csObjForArrayIndex -> csObjForArrayIndex.getObject());
                                    }
                                    return Stream.empty();
                                });
                    }
                    return Stream.empty();
                })
                .filter(obj -> obj != null)
                .map(obj -> {
                    return manager.getSourcePoint(obj);
                })
                .filter(sourcePoint -> {
                    if (sinkPoint.excludeSourceParamAnno() != null && sourcePoint instanceof ParamSourcePoint paramSourcePoint) {
                        return !paramSourcePoint.sourceMethod()
                                .getParamAnnotations(paramSourcePoint.index())
                                .stream().anyMatch(annotation -> sinkPoint.excludeSourceParamAnno().contains(annotation.getType()));
                    }
                    if (sinkPoint.excludeCallSource() != null && sourcePoint instanceof CallSourcePoint callSourcePoint) {
                        return !sinkPoint.excludeCallSource().contains(callSourcePoint.sourceCall().getMethodRef() + "/" + callSourcePoint.index());
                    }
                    return true;
                })
                .map(sourcePoint -> new TaintFlow(sourcePoint, sinkPoint))
                .forEach(taintFlows::add);
    }

    public List<EnhanceSink> getSinks() {
        return sinks;
    }
}
