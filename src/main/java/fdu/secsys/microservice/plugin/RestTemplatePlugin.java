package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.ConstantObj;
import pascal.taie.analysis.pta.core.heap.MergedObj;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.NewArray;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RestTemplatePlugin implements Plugin {

    Logger logger = LogManager.getLogger(RestTemplatePlugin.class);

    Solver solver;

    MultiMap<Var, Invoke> var2InvokeMap = Maps.newMultiMap();

    MultiMap<Var, Pair<String, Var>> var2UrlMap = Maps.newMultiMap();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    @Override
    public void onStart() {
        World.get().getClassHierarchy().applicationClasses().filter(jClass ->  Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))).forEach(jClass -> jClass.getDeclaredMethods().stream().filter(jMethod -> !jMethod.isAbstract()).forEach(jMethod -> {
            jMethod.getIR().invokes(false).forEach(invoke -> {
                JMethod callee = invoke.getMethodRef().resolveNullable();
                if(callee == null) {
                    return;
                }
                if(restTemplateMethodSignatures.contains(callee.getSignature())) {
                    Var firstArg = InvokeUtils.getVar(invoke, 0);
                    if(firstArg != null) {
                        var2InvokeMap.put(firstArg, invoke);
                    }
                }else if(stringMergeMethodSignatures.contains(callee.getSignature())) {
                    String formatTemplate = "";
                    Var arg0 = invoke.getInvokeExp().getArg(0);
                    if(arg0.isConst() && arg0.getConstValue() instanceof StringLiteral stringLiteral) {
                        formatTemplate = stringLiteral.getString();
                    }
                    if(isOnlyPercentS(formatTemplate)) {
                        Var result = invoke.getResult();
                        if(result!=null) {
                            var2UrlMap.put(InvokeUtils.getVar(invoke, 1), new Pair<>(formatTemplate, result));
                        }
                    }
                }
            });
        }));
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Var var = csVar.getVar();
        String mockKey = "mock:";
        if(var2InvokeMap.containsKey(var)) {
            List<String> targetString = new ArrayList<>();
            pts.forEach(csObj -> {
                if(csObj.getObject() instanceof MergedObj mergedObj) {
                    mergedObj.getAllocation().forEach(reprObj -> {
                        if(reprObj instanceof ConstantObj constantObj) {
                            if(constantObj.getAllocation() instanceof StringLiteral stringLiteral) {
                                String string = stringLiteral.getString();
                                if(string.startsWith(mockKey)) {
                                    targetString.add(string.substring(mockKey.length()));
                                }

                            }
                        }
                    });
                }
                if(csObj.getObject() instanceof ConstantObj constantObj) {
                    if(constantObj.getAllocation() instanceof  StringLiteral stringLiteral) {
                        String string = stringLiteral.getString();
                        if(string.startsWith(mockKey)) {
                            targetString.add(string.substring(mockKey.length()));
                        }
                    }
                }
            });
            GatewaySourcePlugin.getEndpoints().forEach(endpoint -> {
                String route = endpoint.getRoute();
                if(!targetString.isEmpty()) {
                    List matchList = targetString.stream().filter(route::endsWith).filter(s -> !s.isBlank() && s.contains("/") && List.of("/", "}").stream().noneMatch(s::equals)).toList();
                    if(!matchList.isEmpty()) {
                        JMethod targetMethod = endpoint.getMethod();
                        CSManager csManager = solver.getCSManager();
                        var2InvokeMap.get(var).forEach(invoke -> {
                            CSCallSite csCallSite = csManager.getCSCallSite(csVar.getContext(), invoke);
                            CSMethod csMethod = csManager.getCSMethod(solver.getContextSelector().getEmptyContext(), targetMethod);
                            solver.addCallEdge(new Edge<>(CallKind.OTHER, csCallSite, csMethod));
                            if(targetMethod.getParamCount() == 1) {
                                solver.addPFGEdge(csManager.getCSVar(csVar.getContext(), InvokeUtils.getVar(invoke, 2)), csManager.getCSVar(solver.getContextSelector().getEmptyContext(), targetMethod.getIR().getParam(0)), FlowKind.PARAMETER_PASSING);
                            }
                        });
                    }
                }
            });
        } else if(var2UrlMap.containsKey(var)) {
            List<String> targetString = new ArrayList<>();
            var.getStoreArrays().forEach(storeArray -> {
                if(storeArray.getRValue().isConst()) {
                    if(storeArray.getRValue().getConstValue() instanceof StringLiteral stringLiteral) {
                        targetString.add(stringLiteral.getString());
                    }
                }
            });
            var2UrlMap.get(var).forEach(pair -> {
                CSManager csManager = solver.getCSManager();
                CSVar csUrlVar = csManager.getCSVar(csVar.getContext(), pair.second());
                String url = formatWithTrimmedTemplate(pair.first(), targetString);
                solver.addPointsTo(csUrlVar, solver.getHeapModel().getConstantObj(StringLiteral.get(String.format("%s%s", mockKey, url))));
            });
        }
    }

    static List<String> restTemplateMethodSignatures = List.of(
            "<org.springframework.web.client.RestTemplate: org.springframework.http.ResponseEntity exchange(java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,org.springframework.core.ParameterizedTypeReference,java.lang.Object[])>",
            "<org.springframework.web.client.RestTemplate: org.springframework.http.ResponseEntity exchange(java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,org.springframework.core.ParameterizedTypeReference,java.util.Map)>");

    static List<String> stringMergeMethodSignatures = List.of("<java.lang.String: java.lang.String format(java.lang.String,java.lang.Object[])>");

    public static String formatWithTrimmedTemplate(String formatTemplate, List<String> value) {
        int valueSize = value.size();
        if(!isOnlyPercentS(formatTemplate)) return "";
        int count = countOccurrences(formatTemplate, "%s");
        String newTemplate = formatTemplate;
        if (count > valueSize) {

            int toRemove = count - valueSize;
            int start = 0;
            for (int i = 0; i < toRemove; i++) {
                int pos = newTemplate.indexOf("%s", start);
                if (pos == -1) break;

                int cutFrom = pos;

                if (cutFrom > 0 && newTemplate.charAt(cutFrom-1) == '/') {
                    cutFrom = cutFrom-1;
                }
                newTemplate = newTemplate.substring(0, cutFrom) + newTemplate.substring(pos+2);

                start = 0;
            }
        }

        Object[] params = value.toArray();
        return String.format(newTemplate, params);
    }

    private static int countOccurrences(String str, String substr) {
        int count = 0, idx = 0;
        while ((idx = str.indexOf(substr, idx)) != -1) {
            count++;
            idx += substr.length();
        }
        return count;
    }

    public static boolean isOnlyPercentS(String formatTemplate) {
        for (int i = 0; i < formatTemplate.length(); i++) {
            if (formatTemplate.charAt(i) == '%') {
                if (i + 1 >= formatTemplate.length()) return false;
                char nextChar = formatTemplate.charAt(i + 1);
                if (nextChar == 's' || nextChar == '%') {
                    i++;
                } else {
                    return false;
                }
            }
        }
        return true;
    }
}
