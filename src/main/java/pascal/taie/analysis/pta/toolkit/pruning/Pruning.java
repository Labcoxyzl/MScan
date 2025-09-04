package pascal.taie.analysis.pta.toolkit.pruning;

import fdu.secsys.microservice.util.ChainUtil;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.taint.*;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Pruning {

    public static Map<JMethod, String> run(PointerAnalysisResult pta, String arg) {
        Map<JMethod, String> csMap = new HashMap<>();
        csMap = csMapByTaintNum(pta, arg);
        return csMap;
    }

    public static Map<JMethod, String> csMapByTaintNum(PointerAnalysisResult pta, String arg) {
        Map<JMethod, String> csMap = new HashMap<>();
        Bar bar = new Bar();
        List<Pair<JMethod, Integer>> pairList = pta.getCallGraph().reachableMethods()
                .map(jMethod -> new Pair<>(jMethod,
                        jMethod.getIR().getParams().stream().map(param -> pta.getPointsToSet(param).stream().filter(TaintManager::isTaintStatic).toList().size()).reduce(0, Integer::sum))
                ).filter(pair -> pair.second() > 0).sorted((o1, o2) -> o2.second() - o1.second()).toList();
        AtomicReference<Double> count = new AtomicReference<>((double) 0);
        AtomicInteger count5 = new AtomicInteger();
        pairList.stream().limit(1000).forEach(pair -> {
            if(count.get() < 1e5) {
                JMethod jMethod = pair.first();
                csMap.put(jMethod, "5");
                count5.getAndIncrement();
                if(!jMethod.isAbstract()) {
                    int varNum = jMethod.getIR().getVars().size();
                    int base = pta.getCallGraph().getCallersOf(jMethod).size();
                    count.set(count.get() + varNum * Math.pow(base, 4));
                }
            }
        });
        return csMap;
    }

    public static Map<JMethod, String> csMapByTaintFlow(PointerAnalysisResult pta, String arg) {
        Map<JMethod, String> csMap = new HashMap<>();
        Set<TaintFlow> taintFlows = pta.getResult(EnhanceTaintAnalysis.class.getName());
        taintFlows.forEach(taintFlow -> {
            SourcePoint sourcePoint = taintFlow.sourcePoint();
            SinkPoint sinkPoint = taintFlow.sinkPoint();
            JMethod sourceMethod = sourcePoint.getContainer();
            JMethod sinkMethod = sinkPoint.sinkCall().getContainer();
            if (sourceMethod != null && sinkMethod != null) {
                List<JMethod> chain = ChainUtil.breadthFirst(pta.getCallGraph(), sourceMethod, sinkMethod);
                if (!chain.isEmpty() && chain.get(0).toString().equals(sourceMethod.toString())) {
                    chain.forEach(jMethod -> csMap.put(jMethod, null));
                }
            }
        });
        return csMap;
    }

    static class Bar {
        static Map<String, Integer> barMap;
        Map<String, Integer> count = new HashMap<>();

        static {
            barMap = new HashMap<>();
            barMap.put("5", 4000);
            barMap.put("4", 4000);
            barMap.put("3", 4000);
        }

        public boolean test(String key) {
            if(!barMap.containsKey(key)) return false;
            int bar = barMap.get(key);
            int value = count.computeIfAbsent(key, (k) -> 0);
            if(value < bar) {
                count.put(key, value+1);
                return true;
            } else {
                return false;
            }
        }

        public void decrease(String key) {
            int value = count.computeIfAbsent(key, (k) -> 1);
            count.put(key, value-1);
        }

        public boolean isEnough(String key) {
            if(!barMap.containsKey(key)) return false;
            int bar = barMap.get(key);
            int value = count.computeIfAbsent(key, (k) -> 0);
            return value < bar;
        }

    }

}
