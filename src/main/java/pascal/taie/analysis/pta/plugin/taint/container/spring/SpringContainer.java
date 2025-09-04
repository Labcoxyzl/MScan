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

package pascal.taie.analysis.pta.plugin.taint.container.spring;

import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.util.GatewayUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.core.solver.SpecifiedParamProvider;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.EnhanceHandlerContext;
import pascal.taie.analysis.pta.plugin.taint.EnhanceTaintManager;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.*;
import java.util.stream.Collectors;

public class SpringContainer implements Plugin {

    private static final Logger logger = LogManager.getLogger(SpringContainer.class);

    private Solver solver;

    private EnhanceTaintManager manager;

    private Map<JClass, SpringBean> springContainersByType = new HashMap<>();
    private Map<String, SpringBean> springContainersByName = new HashMap<>();
    private Map<InjectTargetInfo, InjectFieldInfo> diFieldInfos = new HashMap<>();
    private Map<JMethod, List<InjectParamInfo>> diParamInfos = new HashMap<>();

    public SpringContainer(EnhanceHandlerContext context) {
        this.solver = context.solver();
        manager = context.manager();
    }

    private void addEntryPoint(JMethod m, Obj obj, Map<Integer, Obj> params) {
        SpecifiedParamProvider.Builder builder = new SpecifiedParamProvider.Builder(m);
        DeclaredParamProvider declaredParamProvider = new DeclaredParamProvider(m, solver.getHeapModel(), 1);
        builder.addThisObj(obj);
        params.forEach((index, param) -> builder.addParamObj(index, param));
        builder.setDelegate(declaredParamProvider);
        solver.addEntryPoint(new EntryPoint(m, builder.build()));
    }

    @Override
    public void onStart() {
        Set<JClass> toDIClasses = extractAllBean();

        toDIClasses.forEach(jClass -> {
            jClass.getDeclaredFields().stream()
                    .filter(Utils::isDI)
                    .forEach(jField -> diFieldInfos.put(new InjectTargetInfo(jClass, jField), Utils.getInjectFiledInfo(jField)));

            JClass superClass = jClass.getSuperClass();
            while (superClass != null) {
                superClass.getDeclaredFields().stream().filter(Utils::isDI)
                        .forEach(jField -> diFieldInfos.put(new InjectTargetInfo(jClass, jField), Utils.getInjectFiledInfo(jField)));
                superClass = superClass.getSuperClass();
            }


            List<JMethod> constructors = jClass.getDeclaredMethods().stream().filter(jMethod -> jMethod.isConstructor() && jMethod.getParamCount() > 0).toList();
            if (constructors.size() == 1) {
                // if the bean only has a constructor with parameter, use this constructor
                JMethod constructor = constructors.get(0);
                List<InjectParamInfo> injectParamInfos = Utils.getDefaultInjectParamsInfo(constructor);
                diParamInfos.put(constructor, injectParamInfos);
            } else {
                jClass.getDeclaredMethods().stream()
                        .filter(jMethod -> (jMethod.isConstructor() || Utils.isBeanByConfiguration(jMethod)) && jMethod.getParamCount() > 0)
                        .forEach(jMethod -> {
                            List<InjectParamInfo> injectParamInfos = Utils.getInjectParamsInfo(jMethod);
                            diParamInfos.put(jMethod, injectParamInfos);
                        });
            }
        });

        fieldDI();
        methodDI();
    }

    Set<JMethod> controllers = new HashSet<>();

    private Set<JClass> extractAllBean() {
        ClassHierarchy hierarchy = solver.getHierarchy();
        Set<JClass> toDIClasses = new HashSet<>();
        World.get().getClassHierarchy().allClasses().collect(Collectors.toList()).forEach(jClass -> {
            if(Arrays.stream(Config.classpathKeywords).anyMatch(keyword -> jClass.getName().contains(keyword))) {
                if(Utils.isJavaxRS(jClass)) {
                    Obj obj = manager.makeThis(jClass);
                    jClass.getDeclaredMethods().stream().filter(jMethod -> !jMethod.isAbstract())
                            .filter(jMethod -> Utils.isJavaxRS(jMethod) && GatewayUtil.isExposed(jMethod))
                            .forEach(m -> {controllers.add(m); addEntryPoint(m, obj, Collections.EMPTY_MAP);});
                }
            }
            if (Utils.isBean(jClass) || Utils.isController(jClass)) {
                Obj obj = manager.makeThis(jClass);
                if (Utils.isController(jClass)) {
                    jClass.getDeclaredMethods().stream().filter(jMethod -> !jMethod.isAbstract())
                            .filter(jMethod -> Utils.isController(jMethod) && GatewayUtil.isExposed(jMethod))
                            .forEach(m -> {controllers.add(m); addEntryPoint(m, obj, Collections.EMPTY_MAP);});
                }
                SpringBean springBean = Utils.isController(jClass) ? new SpringBean(Set.of(jClass.getSimpleName().length() >1 ? String.valueOf(jClass.getSimpleName().charAt(0)).toLowerCase() + jClass.getSimpleName().substring(1):String.valueOf(jClass.getSimpleName().charAt(0)).toLowerCase() ), false, obj)  : Utils.getSpringBeanByAnno(jClass, obj);
                if (springBean != null) {
                    springContainersByType.put(jClass, springBean);
                    springBean.getName().forEach(name -> springContainersByName.put(name, springBean));
                    toDIClasses.add(jClass);
                }
            } else if (Utils.isConfiguration(jClass)) {
                jClass.getDeclaredMethods().stream().filter(Utils::isBeanByConfiguration)
                        .forEach(jMethod -> {
                            List<Var> retVars = jMethod.getIR().getReturnVars();
                            for (Var retVar : retVars) {
                                if (retVar == null) return;
                                JClass retJClass = hierarchy.getClass(retVar.getType().getName());
                                if (retJClass != null) {
                                    Obj objByMethodBean = manager.makeThis(retJClass);
                                    SpringBean springBean = Utils.getSpringBeanByMethod(jMethod, objByMethodBean, retVars);
                                    if (springBean != null) {
                                        springContainersByType.put(retJClass, springBean);
                                        springBean.getName().forEach(name -> springContainersByName.put(name, springBean));
                                        toDIClasses.add(retJClass);
                                    }
                                }
                            }

                        });
            }
        });
        logger.info("[+] {} controllers", controllers.size());
        return toDIClasses;
    }

    private void fieldDI() {
        ClassHierarchy hierarchy = solver.getHierarchy();
        CSManager csManager = solver.getCSManager();
        diFieldInfos.forEach((injectTargetInfo, injectFieldInfo) -> {
            SpringBean fieldBean = null;
            JField jField = injectTargetInfo.getjField();
            if (injectFieldInfo.isByType()) {
                JClass fieldClass = hierarchy.getClass(jField.getType().getName());
                List<SpringBean> springBeans = hierarchy.getAllSubclassesOf(fieldClass).stream()
                        .filter(springContainersByType::containsKey)
                        .map(springContainersByType::get).collect(Collectors.toList());
                if (springBeans.size() > 1) {
                    Optional<SpringBean> optional = springBeans.stream()
                            .filter(SpringBean::isPrimary).findFirst();
                    if (optional.isPresent()) {
                        fieldBean = optional.get();
                    } else {
                        for (SpringBean fieldBeanForList : springBeans) {
                            fieldInject(csManager, injectTargetInfo, injectFieldInfo, fieldBeanForList);
                        }
                        return;
                    }
                } else if (springBeans.size() == 1) {
                    fieldBean = springBeans.get(0);
                }
            } else if (injectFieldInfo.getQualifier() != null && !injectFieldInfo.getQualifier().equals("")) {
                fieldBean = springContainersByName.get(injectFieldInfo.getQualifier());
            }
            fieldInject(csManager, injectTargetInfo, injectFieldInfo, fieldBean);
        });
    }

    private void fieldInject(CSManager csManager, InjectTargetInfo injectTargetInfo , InjectFieldInfo injectFieldInfo, SpringBean fieldBean) {
        JField jField = injectTargetInfo.getjField();
        JClass jClass = injectTargetInfo.getjClass();
        if (fieldBean != null && springContainersByType.containsKey(jClass)) {
            SpringBean declaringClassBean = springContainersByType.get(jClass);
            CSObj csObj = csManager.getCSObj(solver.getContextSelector().getEmptyContext(), declaringClassBean.getObj());
            InstanceField iField = csManager.getInstanceField(csObj, jField);
            solver.addPointsTo(iField, fieldBean.getObj());
            if (fieldBean.getRetVars() != null) {
                for (Var retVar : fieldBean.getRetVars()) {
                    CSVar csRetVar = csManager.getCSVar(solver.getContextSelector().getEmptyContext(), retVar);
                    solver.addPFGEdge(csRetVar, iField, FlowKind.OTHER);
                }
            }
        } else {
            logger.error("Can't find the field:{}/{} DI bean:{}", jField.getDeclaringClass().getName(), jField.getName(), injectFieldInfo.isByType() ? "byType" : injectFieldInfo.getQualifier());
        }
    }

    private void methodDI() {
        ClassHierarchy hierarchy = solver.getHierarchy();
        CSManager csManager = solver.getCSManager();
        diParamInfos.forEach((jMethod, injectParamInfos) -> {
            SpringBean declaringClassBean = springContainersByType.get(jMethod.getDeclaringClass());
            if (declaringClassBean != null) {
                Map<Integer, Obj> paramObjs = new TreeMap();
                injectParamInfos.forEach(injectParamInfo -> {
                    SpringBean paramBean = null;
                    if (injectParamInfo.isByType()) {
                        JClass jClass = hierarchy.getClass(injectParamInfo.getType());
                        List<SpringBean> springBeans = hierarchy.getAllSubclassesOf(jClass).stream()
                                .filter(springContainersByType::containsKey)
                                .map(springContainersByType::get).collect(Collectors.toList());
                        if (springBeans.size() > 1) {
                            Optional<SpringBean> optional = springBeans.stream()
                                    .filter(SpringBean::isPrimary).findFirst();
                            if (optional.isPresent()) {
                                paramBean = optional.get();
                            } else {
                                for (SpringBean paramBeanByList : springBeans) {
                                    paramInject(csManager, jMethod, paramObjs, injectParamInfo, paramBeanByList);
                                }
                                return;
                            }
                        } else if (springBeans.size() == 1) {
                            paramBean = springBeans.get(0);
                        }
                    } else if (injectParamInfo.getQualifier() != null) {
                        paramBean = springContainersByName.get(injectParamInfo.getQualifier());
                    }
                    paramInject(csManager, jMethod, paramObjs, injectParamInfo, paramBean);
                });
                addEntryPoint(jMethod, declaringClassBean.getObj(), paramObjs);
            }
        });
    }

    private void paramInject(CSManager csManager, JMethod jMethod, Map<Integer, Obj> paramObjs, InjectParamInfo injectParamInfo, SpringBean paramBean) {
        if (paramBean != null) {
            paramObjs.put(injectParamInfo.getIndex(), paramBean.getObj());
            if (paramBean.getRetVars() != null) {
                Var param = jMethod.getIR().getParam(injectParamInfo.getIndex());
                CSVar csParamVar = csManager.getCSVar(solver.getContextSelector().getEmptyContext(), param);
                for (Var retVar : paramBean.getRetVars()) {
                    CSVar csRetVar = csManager.getCSVar(solver.getContextSelector().getEmptyContext(), retVar);
                    solver.addPFGEdge(csRetVar, csParamVar, FlowKind.OTHER);
                }
            }
        } else {
            logger.error("Can't find the constructor param:{}/{} DI bean:{}", jMethod.getSubsignature().toString(), injectParamInfo.getIndex(), injectParamInfo.isByType() ? "byType" : injectParamInfo.getQualifier());
        }
    }
}
