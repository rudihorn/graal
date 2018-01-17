/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.nodes;

import jdk.vm.ci.code.BytecodePosition;

import java.util.ArrayList;
import java.util.List;

public class InliningLog {
    public static final class Decision {
        private final boolean positive;
        private final String reason;
        private final List<Object> phaseStack;
        private final BytecodePosition position;

        private Decision(boolean positive, String reason, Object phase, BytecodePosition position) {
            this.positive = positive;
            this.reason = reason;
            this.phaseStack = new ArrayList<>();
            this.phaseStack.add(phase);
            this.position = position;
        }

        public boolean isPositive() {
            return positive;
        }

        public String getReason() {
            return reason;
        }

        public List<Object> getPhaseStack() {
            return phaseStack;
        }

        public BytecodePosition getPosition() {
            return position;
        }

        public Decision appendAt(List<Object> phasesAtInline, BytecodePosition inlinePosition) {
            return new Decision(positive, reason, prependPhasesAtInline(phasesAtInline), position.addCaller(inlinePosition));
        }

        private ArrayList<Object> prependPhasesAtInline(List<Object> phasesAtInline) {
            ArrayList<Object> appendedPhaseStack = new ArrayList<>();
            appendedPhaseStack.addAll(phasesAtInline);
            appendedPhaseStack.addAll(phaseStack);
            return appendedPhaseStack;
        }
    }

    private final List<Decision> decisions;

    public InliningLog() {
        this.decisions = new ArrayList<>();
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public void addDecision(boolean positive, String reason, Object phase, BytecodePosition position) {
        Decision decision = new Decision(positive, reason, phase, position);
        decisions.add(decision);
    }

    public void addDecisionsFromInlinedGraph(List<Object> phasesAtInline, BytecodePosition inlinePosition, StructuredGraph graph) {
        for (Decision decision : graph.getInliningLog().getDecisions()) {
            Decision appendedDecision = decision.appendAt(phasesAtInline, inlinePosition);
            decisions.add(appendedDecision);
        }
    }

}
