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

import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.Set;

/**
 * Represents a program location where taint objects flow to a sink.
 *
 * @param sinkCall               call site of the sink method.
 * @param index                  index of the sensitive argument at {@code sinkCall}.
 * @param callee
 * @param var
 * @param excludeSourceParamAnno
 * @param excludeCallSource
 * @param vulId
 */
public record SinkPoint(Invoke sinkCall, int index, JMethod callee, Var var,
                        Set<String> excludeSourceParamAnno, Set<String> excludeCallSource,
                        String vulId) implements Comparable<SinkPoint> {

    private static final Comparator<SinkPoint> COMPARATOR =
            Comparator.comparing(SinkPoint::comparing)
                    .thenComparingInt(SinkPoint::index);

    @Override
    public int compareTo(@Nonnull SinkPoint other) {
        return COMPARATOR.compare(this, other);
    }

    public String comparing() {
        return (sinkCall != null ? sinkCall.toString() : callee.getSignature());
    }

    @Override
    public String toString() {
        return (sinkCall != null ? sinkCall : callee.getSignature()) + "/" + InvokeUtils.toString(index) + " --- VUL_ID:" + vulId;
    }
}
