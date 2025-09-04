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

package pascal.taie.analysis.pta.plugin.taint.dynamic.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.EnhanceHandlerContext;
import pascal.taie.analysis.pta.plugin.taint.EnhanceTaintManager;
import pascal.taie.analysis.pta.plugin.taint.ParamSourcePoint;
import pascal.taie.analysis.pta.plugin.taint.SourcePoint;
import pascal.taie.analysis.pta.plugin.taint.container.spring.Utils;
import pascal.taie.config.OptionsHolder;
import pascal.taie.ir.IR;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.HashSet;
import java.util.Set;

public class SpringController implements Plugin {

    private static final Logger logger = LogManager.getLogger(SpringController.class);

    private Solver solver;
    private EnhanceTaintManager manager;

    // whether taint fields by default
    private static boolean IS_TAINT_FIELD = true;

    public SpringController(EnhanceHandlerContext context) {
        this.solver = context.solver();
        manager = context.manager();
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        if (Utils.isController(method)) {
            Context context = csMethod.getContext();
            IR ir = method.getIR();
            ir.getParams().stream().filter(Utils::isControlParam).forEach(param -> {
                SourcePoint sourcePoint = new ParamSourcePoint(method, method.isStatic() ? param.getIndex() : param.getIndex() - 1);
                Type type = param.getType();
                Obj taint = manager.makeTaint(sourcePoint, type, true);
                taintField(context, sourcePoint, type, taint, new HashSet<>(), 0);
                solver.addVarPointsTo(context, param, taint);
            });
        }
    }

    private void taintField(Context context, SourcePoint sourcePoint, Type type, Obj taint, Set<Type> jFields, Integer layer) {
        if (!IS_TAINT_FIELD) {
            if (jFields.add(type)) return;
        }
        if (layer >= 4) {
            return;
        }
        for (String classpathKeyword : OptionsHolder.options.getClasspathKeywords()) {
            while (type.getName().contains(classpathKeyword)) {
                CSObj csObj = solver.getCSManager().getCSObj(context, taint);
                JClass jClass = solver.getHierarchy().getClass(type.getName());
                jClass.getDeclaredFields().forEach(jField -> {
                    InstanceField instanceField = solver.getCSManager().getInstanceField(csObj, jField);
                    Obj fieldTaint = manager.makeTaint(sourcePoint, jField.getType(), true);
                    taintField(context, sourcePoint, jField.getType(), fieldTaint, new HashSet<>(), layer + 1);
                    solver.addPointsTo(instanceField, fieldTaint);
                });
                type = jClass.getSuperClass().getType();
            }
        }
    }
}
