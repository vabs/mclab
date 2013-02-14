// =========================================================================== //
//                                                                             //
// Copyright 2011 Jesse Doherty and McGill University.                         //
//                                                                             //
//   Licensed under the Apache License, Version 2.0 (the "License");           //
//   you may not use this file except in compliance with the License.          //
//   You may obtain a copy of the License at                                   //
//                                                                             //
//       http://www.apache.org/licenses/LICENSE-2.0                            //
//                                                                             //
//   Unless required by applicable law or agreed to in writing, software       //
//   distributed under the License is distributed on an "AS IS" BASIS,         //
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  //
//   See the License for the specific language governing permissions and       //
//  limitations under the License.                                             //
//                                                                             //
// =========================================================================== //

package natlab.toolkits.analysis.core;

import java.util.HashSet;
import java.util.Set;

import natlab.toolkits.analysis.HashMapFlowMap;
import natlab.toolkits.analysis.Merger;
import natlab.toolkits.analysis.Mergers;
import natlab.utils.NodeFinder;
import analysis.AbstractSimpleStructuralForwardAnalysis;
import ast.ASTNode;
import ast.AssignStmt;
import ast.Function;
import ast.GlobalStmt;
import ast.MatrixExpr;
import ast.Name;
import ast.Script;
import ast.Stmt;

import com.google.common.base.Predicate;
import com.google.common.collect.Sets;

/**
 * A simple forward analysis example, computing reaching definitions.
 * 
 * @author Jesse Doherty
 */
public class ReachingDefs extends
        AbstractSimpleStructuralForwardAnalysis<HashMapFlowMap<String, Set<Def>>> {
  public static Def UNDEF = new AssignStmt();

  private Set<Name> defs = Sets.newHashSet();
  private Merger<Set<Def>> merger = Mergers.union();

  private HashMapFlowMap<String, Set<Def>> startMap;
  private DefinitelyAssignedAnalysis definiteAssignment;
  private NameCollector nameCollector;

  public ReachingDefs(ASTNode<?> tree) {
    super(tree);
  }
  
  @Override
  public void analyze() {
    startMap = new HashMapFlowMap<String, Set<Def>>(merger);
    nameCollector = new NameCollector(tree);
    nameCollector.analyze();
    definiteAssignment = new DefinitelyAssignedAnalysis(tree);
    definiteAssignment.analyze();
    for (Name var : nameCollector.getAllNames()) {
      startMap.put(var.getID(), new HashSet<Def>());
    }
    super.analyze();
  }

  @Override
  public void merge(HashMapFlowMap<String, Set<Def>> in1,
          HashMapFlowMap<String, Set<Def>> in2, HashMapFlowMap<String, Set<Def>> out) {
    in1.union(merger, in2, out);
  }

  @Override
  public void copy(HashMapFlowMap<String, Set<Def>> in,
          HashMapFlowMap<String, Set<Def>> out) {
    if (in == out)
      return;
    out.clear();
    for (String i : in.keySet())
      out.put(i, Sets.newHashSet(in.get(i)));
  }

  public HashMapFlowMap<String, Set<Def>> copy(HashMapFlowMap<String, Set<Def>> in) {
    HashMapFlowMap<String, Set<Def>> out = new HashMapFlowMap<String, Set<Def>>();
    copy(in, out);
    return out;
  }

  @Override
  public void caseFunction(Function f) {
    currentOutSet = newInitialFlow();
    currentInSet = currentOutSet;
    for (Name n : NodeFinder.find(Name.class, f.getStmts())) {
      currentOutSet.put(n.getID(), Sets.<Def> newHashSet(UNDEF));
    }
    for (Name inputArg : f.getInputParamList()) {
      currentOutSet.put(inputArg.getID(), Sets.<Def> newHashSet(inputArg));
    }
    caseASTNode(f.getStmts());
    outFlowSets.put(f, currentOutSet);
  }

  @Override
  public void caseScript(Script f) {
    currentOutSet = newInitialFlow();
    currentInSet = currentOutSet;
    for (Name n : NodeFinder.find(Name.class, f)) {
      currentOutSet.put(n.getID(), Sets.<Def> newHashSet(UNDEF));
    }
    caseASTNode(f.getStmts());
    outFlowSets.put(f, currentOutSet);
  }

  @Override
  public HashMapFlowMap<String, Set<Def>> newInitialFlow() {
    return copy(startMap);
  }
  
  private boolean isSimpleLValue(Name node) {
    return node.getParent().getParent() instanceof AssignStmt
        || node.getParent().getParent() instanceof MatrixExpr;
  }

  private Set<Name> getSimpleDefs(final AssignStmt node) {
    return Sets.filter(nameCollector.getNames(node), new Predicate<Name>() {
      @Override public boolean apply(Name name) {
        return isSimpleLValue(name);
      }
    });
  }
  
  private Set<Name> getImplicitDefs(final AssignStmt node) {
    return Sets.filter(nameCollector.getNames(node), new Predicate<Name>() {
      @Override public boolean apply(Name name) {
        return !isSimpleLValue(name) 
            && !definiteAssignment.isDefinitelyAssignedAtInputOf(node, name.getID());
      }
    });
  }

  @Override
  public void caseAssignStmt(AssignStmt node) {
    currentOutSet = copy(currentInSet);
    for (Name n : getSimpleDefs(node)) {
      defs.add(n);
      currentOutSet.put(n.getID(), Sets.<Def> newHashSet(node));
    }
    for (Name n : getImplicitDefs(node)) {
      defs.add(n);
      currentInSet.get(n.getID()).remove(UNDEF);
      currentInSet.get(n.getID()).add(node);
      currentOutSet.get(n.getID()).remove(UNDEF);
      currentOutSet.get(n.getID()).add(node);
    }
    inFlowSets.put(node, currentInSet);
    outFlowSets.put(node, currentOutSet);
  }

  @Override
  public void caseStmt(Stmt node) {
    inFlowSets.put(node, currentInSet);
    currentOutSet = copy(currentInSet);
    outFlowSets.put(node, currentOutSet);
  }
  
  @Override
  public void caseGlobalStmt(GlobalStmt node) {
    inFlowSets.put(node, currentInSet);
    currentOutSet = copy(currentInSet);
    for (Name name : node.getNames()) {
      currentOutSet.put(name.getID(), Sets.<Def>newHashSet(node));
    }
    outFlowSets.put(node, currentOutSet);
  }
  
  public boolean isDef(Name name) {
    return defs.contains(name);
  }

  private UseDefDefUseChain udduChainCached = null;

  public UseDefDefUseChain getUseDefDefUseChain() {
    if (udduChainCached == null) {
      udduChainCached = UseDefDefUseChain.fromReachingDefs(this);
    }
    return udduChainCached;
  }
}
