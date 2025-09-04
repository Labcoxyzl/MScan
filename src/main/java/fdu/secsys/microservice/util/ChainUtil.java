package fdu.secsys.microservice.util;

import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.*;
import java.util.stream.Collectors;

public class ChainUtil {

    static int MAX_DEPTH = 14;

    static int MAX_ITER = 100000;

    public static List<JMethod> breadthFirst(CallGraph<Invoke, JMethod> callGraph, JMethod source, JMethod target) {
        List<JMethod> chain = new ArrayList<>();
        if (source.equals(target)) {
            chain.add(target);
            return chain;
        }
        Queue<JMethod> queue = new LinkedList<>();
        HashMap<JMethod, JMethod> backPointer = new HashMap<>();
        HashMap<JMethod, Integer> backDepth = new HashMap<>();
        backDepth.put(source, 0);
        queue.add(source);
        int count = 0;
        while (!queue.isEmpty() && count < MAX_ITER) {
            count++;
            JMethod element = queue.poll();
            Set<JMethod> successors = callGraph.getSuccsOf(element);
            if (successors.stream().anyMatch(m -> m.equals(target))) {
                JMethod call = element;
                chain.add(0, call);
                while (backPointer.containsKey(call) && chain.size() <= MAX_DEPTH) {
                    JMethod backMethod = backPointer.get(call);
                    if (call.equals(backMethod)) break;
                    call = backMethod;
                    chain.add(0, call);
                }
                chain.add(target);
                return chain;
            }

            successors = successors
                    .stream()
                    .filter(m -> !m.isAbstract() && m.getDeclaringClass().isApplication() && backDepth.get(element) + 1 < MAX_DEPTH)
                    .collect(Collectors.toSet());

            successors.forEach(m -> {
                backDepth.put(m, backDepth.get(element) + 1);
                backPointer.put(m, element);
            });

            queue.addAll(successors);
        }
        return chain;
    }

    public static List<JMethod> breadthFirstMaxIter(CallGraph<Invoke, JMethod> callGraph, JMethod source, JMethod target, int max_iter) {
        List<JMethod> chain = new ArrayList<>();
        if (source.equals(target)) {
            chain.add(target);
            return chain;
        }
        Queue<JMethod> queue = new LinkedList<>();
        HashMap<JMethod, JMethod> backPointer = new HashMap<>();
        HashMap<JMethod, Integer> backDepth = new HashMap<>();
        backDepth.put(source, 0);
        queue.add(source);
        int count = 0;
        while (!queue.isEmpty() && count < max_iter) {
            count++;
            JMethod element = queue.poll();
            Set<JMethod> successors = callGraph.getSuccsOf(element);
            if (successors.stream().anyMatch(m -> m.equals(target))) {
                JMethod call = element;
                chain.add(0, call);
                while (backPointer.containsKey(call) && chain.size() <= MAX_DEPTH ) {
                    JMethod backMethod = backPointer.get(call);
                    if (call.equals(backMethod)) break;
                    call = backMethod;
                    chain.add(0, call);
                }
                chain.add(target);
                return chain;
            }

            successors = successors
                    .stream()
                    .filter(m -> !m.isAbstract() && m.getDeclaringClass().isApplication() && backDepth.get(element) + 1 < MAX_DEPTH)
                    .collect(Collectors.toSet());

            successors.forEach(m -> {
                backDepth.put(m, backDepth.get(element) + 1);
                backPointer.put(m, element);
            });

            queue.addAll(successors);
        }
        return chain;
    }
}
