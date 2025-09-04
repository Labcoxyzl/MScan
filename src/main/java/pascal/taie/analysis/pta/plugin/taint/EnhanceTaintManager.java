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

import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

/**
 * Manages taint objects.
 */
public class EnhanceTaintManager extends TaintManager {

    private static final Descriptor THIS_DESC = () -> "ThisObj";

    private final HeapModel heapModel;

    EnhanceTaintManager(HeapModel heapModel) {
        super(heapModel);
        this.heapModel = heapModel;
    }

    public Obj makeThis(JClass thisVar) {
        return heapModel.getMockObj(THIS_DESC, thisVar, thisVar.getType());
    }

    public MultiMap<JField, Obj> getTaintFieldObjs(Solver solver,  Obj obj) {
        CSManager csManager = solver.getCSManager();
        ContextSelector contextSelector = solver.getContextSelector();
        MultiMap<JField, Obj> fieldObjs = Maps.newMultiMap();
        try {
            ClassType classType = (ClassType) obj.getType();
            JClass jClass = classType.getJClass();
            if (jClass.isAbstract() && jClass.isApplication()) {
                return fieldObjs;
            }
            jClass.getDeclaredFields().forEach(jField -> {
                CSObj CSBaseObj = csManager.getCSObj(contextSelector.getEmptyContext(), obj);
                InstanceField instanceField = solver.getCSManager().getInstanceField(CSBaseObj, jField);
                PointsToSet pointsToSet = solver.getPointsToSetOf(instanceField);
                pointsToSet.forEach(CSFieldObj -> {
                    if (isTaint(CSFieldObj.getObject())) {
                        fieldObjs.put(jField, CSFieldObj.getObject());
                    }
                });
            });
        } catch (Exception e) {
            return fieldObjs;
        }
        return fieldObjs;
    }

}
