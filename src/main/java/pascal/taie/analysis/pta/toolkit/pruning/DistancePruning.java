package pascal.taie.analysis.pta.toolkit.pruning;

import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.util.ChainUtil;
import fdu.secsys.microservice.util.GatewayUtil;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.taint.EnhanceSink;
import pascal.taie.analysis.pta.plugin.taint.EnhanceTaintConfig;
import pascal.taie.analysis.pta.plugin.taint.container.spring.Utils;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.MultiMapCollector;

import java.util.*;
import java.util.function.Predicate;

public class DistancePruning {
    public static Map<JMethod, String> run(PointerAnalysisResult pta,  AnalysisOptions options, List<EnhanceSink> enhanceSinks) {
        Map<JMethod, String> csMap = new HashMap<>();
        List<JMethod> entries = new ArrayList<>();
        World.get().getClassHierarchy().allClasses().forEach(jClass -> {
            if (Utils.isController(jClass)) {
                jClass.getDeclaredMethods().stream()
                        .filter(jMethod -> Utils.isController(jMethod) && GatewayUtil.isExposed(jMethod)).forEach(jMethod -> {
                            entries.add(jMethod);
                        });}
            });

        MultiMap<String, EnhanceSink> sinkMap = enhanceSinks.stream()
                .collect(MultiMapCollector.get(s -> s.methodSig(), s -> s));

        List<JMethod> directSinks = new ArrayList<>();

        World.get().getClassHierarchy().applicationClasses().filter(jClass -> Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))).forEach(jClass -> jClass.getDeclaredMethods().stream().filter(jMethod -> !jMethod.isAbstract()).forEach(jMethod -> jMethod.getIR().invokes(false).forEach(invoke -> {
            MethodRef methodRef = invoke.getMethodRef();
            if(sinkMap.containsKey(methodRef.toString())) {
                directSinks.add(jMethod);
                csMap.put(jMethod, "MAX");
            }
        })));

        MultiMap< JClass, JMethod> progressMap = Maps.newMultiMap();

        World.get().getClassHierarchy().applicationClasses().filter(jClass -> Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))).forEach(jClass -> jClass.getDeclaredMethods().stream().forEach(jMethod -> {
            progressMap.put(jClass, jMethod);
        }));

        List<Map.Entry<JClass, JMethod>> progressList = progressMap.entrySet().stream().toList();
        Set<JMethod> maxSet = new HashSet<>();
        Set<JMethod> minSet = new HashSet<>();
        Set<JMethod> entryReachable = new HashSet<>();
        Set<JMethod> sinkReachable = new HashSet<>();
        int i = 0;
        while (i < progressList.size()) {
            Map.Entry<JClass, JMethod> pair = progressList.get(i);
            JMethod jMethod = pair.getValue();
            if(directSinks.stream().anyMatch(sink -> pta.getCallGraph().getCalleesOfM(jMethod).stream().anyMatch(sinkReachable::contains) || isReachable(pta.getCallGraph(), jMethod, sink))) {
                sinkReachable.add(jMethod);
                if(entries.stream().anyMatch(entry -> pta.getCallGraph().getCallersOf(jMethod).stream().anyMatch(callSite -> entryReachable.contains(callSite.getContainer())) || isReachable(pta.getCallGraph(), entry, jMethod))) {
                    entryReachable.add(jMethod);
                    maxSet.add(jMethod);
                    i++;
                    continue;
                }
            }
            i++;
            minSet.add(jMethod);
        }
        maxSet.forEach(jMethod -> csMap.put(jMethod, "MAX"));
        maxSet.forEach(maxMethod -> pta.getCallGraph().getCalleesOfM(maxMethod).stream().filter(Predicate.not(csMap::containsKey)).forEach(jMethod -> {
            csMap.put(jMethod, "2");
        }));
        minSet.stream().filter(Predicate.not(csMap::containsKey)).forEach(jMethod -> csMap.put(jMethod, "MIN"));
        return csMap;
    }

    public static boolean isReachable(CallGraph<Invoke, JMethod> callGraph, JMethod start, JMethod end) {
        if(start.equals(end)) return true;
        List<JMethod> chain = ChainUtil.breadthFirstMaxIter(callGraph, start, end, 10000);
        return !chain.isEmpty();
    }
}
